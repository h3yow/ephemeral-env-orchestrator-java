package com.github.h3yow.ephemeralenvorchestratorjava.tests;

import com.github.h3yow.ephemeralenvorchestratorjava.domain.FailureCode;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.LeaseStatus;
import com.github.h3yow.ephemeralenvorchestratorjava.error.BusinessException;
import com.github.h3yow.ephemeralenvorchestratorjava.error.ErrorCode;
import com.github.h3yow.ephemeralenvorchestratorjava.tests.support.ScriptedProvisioner;
import com.github.h3yow.ephemeralenvorchestratorjava.tests.support.TestHarness;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.ApplyLeaseRequest;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.LeaseView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 供给失败处理：失败记录保留可解释、资源不足与其它失败区分、重试上限不静默成功、
 * 失败终态释放配额、供给进行中终止的并发资源回收。
 */
class ProvisionFailureRetryTest {

    private static final Logger log = LoggerFactory.getLogger(ProvisionFailureRetryTest.class);

    private TestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new TestHarness(2L, 100L);
    }

    private ApplyLeaseRequest req(String id, String owner) {
        ApplyLeaseRequest r = new ApplyLeaseRequest();
        r.setRequestId(id);
        r.setOwner(owner);
        r.setTeam("teamA");
        r.setTtl("PT10M");
        return r;
    }

    @Test
    @DisplayName("资源不足失败原因可区分并保留记录，重试成功后记录仍可审计")
    void exhaustedThenRetrySuccess() {
        harness.provisioner.setScript(ScriptedProvisioner.Mode.EXHAUSTED, 1);
        LeaseView failed = harness.service.apply(req("p-1", "alice"));
        assertEquals(LeaseStatus.FAILED, failed.status());
        assertEquals(1, failed.failures().size());
        assertEquals(FailureCode.RESOURCE_POOL_EXHAUSTED, failed.failures().get(0).code());
        assertTrue(failed.failures().get(0).message().contains("资源池"));
        log.info("首次供给失败（资源不足），记录={} 配额仍占用={}", failed.failures(), failed.quotaHeld());

        LeaseView ready = harness.service.retry(failed.id(), "alice");
        assertEquals(LeaseStatus.READY, ready.status());
        assertNotNull(ready.endpoint());
        assertEquals(1, ready.failures().size(), "判定依据：成功后历史失败记录必须保留");
        log.info("重试成功 status={} endpoint={} 历史失败数={}", ready.status(), ready.endpoint(),
                ready.failures().size());
    }

    @Test
    @DisplayName("达到重试上限后 FAILED 为终态：不得静默成功、释放配额、再重试被明确拒绝")
    void retryLimitTerminatesFailure() {
        // 前 3 次（含首次）全部瞬时失败
        harness.provisioner.setScript(ScriptedProvisioner.Mode.FAIL, 3);
        LeaseView failed = harness.service.apply(req("p-2", "alice"));
        assertEquals(LeaseStatus.FAILED, failed.status());
        assertEquals(1, failed.failures().size());

        LeaseView v2 = harness.service.retry(failed.id(), "alice");
        assertEquals(LeaseStatus.FAILED, v2.status());
        assertEquals(2, v2.failures().size());
        assertEquals(1L, harness.ledger.ownerView("alice").used(), "判定依据：仍可重试时占用配额");

        LeaseView v3 = harness.service.retry(failed.id(), "alice");
        assertEquals(LeaseStatus.FAILED, v3.status());
        assertEquals(3, v3.failures().size(), "判定依据：三次失败记录全部保留");
        assertFalse(v3.quotaHeld(), "判定依据：达到上限立即释放配额，失败不长期占用");
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        log.info("达到上限：failures={} 配额已释放，消息={}", v3.failures(),
                v3.failures().get(2).message());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> harness.service.retry(failed.id(), "alice"));
        assertEquals(ErrorCode.RETRY_LIMIT_EXCEEDED, ex.getCode(),
                "判定依据：超限后必须以明确原因拒绝，不能静默视为成功");
        log.info("超限再重试被拒绝 code={} message={}", ex.getCode(), ex.getMessage());

        // 释放出的配额可被新申请使用
        LeaseView other = harness.service.apply(req("p-2b", "alice"));
        assertEquals(LeaseStatus.READY, other.status());
        assertEquals(1L, harness.ledger.ownerView("alice").used());
    }

    @Test
    @DisplayName("供给器抛异常被捕获记为失败，可在后续尝试中恢复")
    void provisionerExceptionRecoverable() {
        harness.provisioner.setScript(ScriptedProvisioner.Mode.THROW, 1);
        LeaseView failed = harness.service.apply(req("p-3", "alice"));
        assertEquals(LeaseStatus.FAILED, failed.status());
        assertEquals(FailureCode.PROVISIONER_TRANSIENT, failed.failures().get(0).code());
        log.info("供给异常被捕获并记录：{}", failed.failures().get(0).message());

        LeaseView ready = harness.service.retry(failed.id(), "alice");
        assertEquals(LeaseStatus.READY, ready.status());
    }

    @Test
    @DisplayName("非 FAILED 状态重试被拒绝且原因可区分")
    void retryOnWrongState() {
        LeaseView ready = harness.service.apply(req("p-4", "alice"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> harness.service.retry(ready.id(), "alice"));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ex.getCode());
        log.info("READY 状态重试被拒绝 code={}", ex.getCode());
    }

    @Test
    @DisplayName("供给进行中并发终止：终态优先，成功分配的资源被回收，无泄漏、配额恰好释放一次")
    void terminateDuringProvisioning() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        harness.provisioner.armBlock(entered, release);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        final LeaseView[] holder = new LeaseView[1];
        CountDownLatch applyReturned = new CountDownLatch(1);

        // 申请线程会阻塞在供给器里
        pool.submit(() -> {
            holder[0] = harness.service.apply(req("p-5", "alice"));
            applyReturned.countDown();
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS), "判定依据：确认供给已进入并被阻塞");

        // 此时租约处于 PROVISIONING，并发终止
        // 通过 requestId 找到 leaseId（申请尚未返回）
        final String[] leaseId = new String[1];
        for (int i = 0; i < 50 && leaseId[0] == null; i++) {
            try {
                leaseId[0] = harness.service.getByRequestId("p-5").id();
            } catch (Exception ignore) {
                Thread.sleep(20);
            }
        }
        assertNotNull(leaseId[0]);
        log.info("===== 输入：供给阻塞期间并发终止 leaseId={}，随后放行供给 =====", leaseId[0]);
        assertEquals(1L, harness.ledger.ownerView("alice").used());

        LeaseView terminated = harness.service.terminate(leaseId[0], "alice");
        assertEquals(LeaseStatus.TERMINATED, terminated.status());
        assertEquals(0L, harness.ledger.ownerView("alice").used(), "判定依据：终止即释放配额");

        // 放行被阻塞的供给（其会成功分配到一个槽位）
        release.countDown();
        assertTrue(applyReturned.await(5, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        // 申请返回的视图应反映终态；分配出的槽位必须已被回收
        assertEquals(LeaseStatus.TERMINATED, holder[0].status(),
                "判定依据：供给完成裁决识别终态，不允许 READY 覆盖 TERMINATED");
        assertEquals(0, harness.provisioner.getOccupiedCount(),
                "判定依据：供给期间终止，成功拿到的资源槽必须回收，不得泄漏");
        assertEquals(0L, harness.ledger.ownerView("alice").used(),
                "判定依据：配额不得被二次释放/重新占用");
        log.info("判定结果：供给晚于终止完成，终态=TERMINATED，占用槽位={} 配额={}",
                harness.provisioner.getOccupiedCount(), harness.ledger.ownerView("alice").used());
    }
}
