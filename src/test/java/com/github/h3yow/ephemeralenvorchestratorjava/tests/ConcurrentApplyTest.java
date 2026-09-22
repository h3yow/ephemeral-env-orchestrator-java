package com.github.h3yow.ephemeralenvorchestratorjava.tests;

import com.github.h3yow.ephemeralenvorchestratorjava.error.BusinessException;
import com.github.h3yow.ephemeralenvorchestratorjava.error.ErrorCode;
import com.github.h3yow.ephemeralenvorchestratorjava.tests.support.TestHarness;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.ApplyLeaseRequest;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.LeaseView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发申请测试：同一 requestId 重复提交不重复分配；不同 requestId 并发不超卖配额。
 */
class ConcurrentApplyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentApplyTest.class);

    private TestHarness harness;

    @BeforeEach
    void setUp() {
        // owner=3 便于观察并发下精确的成功数量
        harness = new TestHarness(3L, 100L);
    }

    private ApplyLeaseRequest req(String requestId, String owner, String team) {
        ApplyLeaseRequest r = new ApplyLeaseRequest();
        r.setRequestId(requestId);
        r.setOwner(owner);
        r.setTeam(team);
        r.setTtl("PT10M");
        return r;
    }

    @Test
    @DisplayName("同一 requestId 并发重复提交：恰好一个租约、一次资源分配，所有线程看到同一结论")
    void duplicateRequestConcurrent() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<LeaseView> views = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();

        log.info("===== 输入：{} 个线程同时提交同一 requestId=dup-1 owner=alice =====", threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    views.add(harness.service.apply(req("dup-1", "alice", "teamA")));
                } catch (BusinessException ex) {
                    errors.add(ex.getCode().name() + ":" + ex.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        assertEquals(threads, views.size(), "判定依据：所有重复提交都应成功返回同一个租约视图");
        assertTrue(errors.isEmpty(), "判定依据：幂等命中不是错误：" + errors);
        Set<String> leaseIds = new HashSet<>();
        views.forEach(v -> leaseIds.add(v.id()));
        assertEquals(1, leaseIds.size(), "判定依据：只允许创建一个租约");
        assertEquals(1, harness.provisioner.getSuccessCount(),
                "判定依据：重复提交不得重复分配资源，实际供给成功次数必须为 1");
        assertEquals(1L, harness.ledger.ownerView("alice").used(),
                "判定依据：只占用 1 个配额");
        log.info("判定结果：{} 个并发请求 -> 唯一 leaseId={} 资源分配次数={} owner used={}",
                threads, leaseIds.iterator().next(), harness.provisioner.getSuccessCount(),
                harness.ledger.ownerView("alice").used());
    }

    @Test
    @DisplayName("不同 requestId 并发申请：成功数严格等于配额上限，绝不超卖，拒绝原因一致为配额")
    void noOversellUnderContention() throws Exception {
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<LeaseView> ok = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ErrorCode> rejected = new ConcurrentLinkedQueue<>();

        log.info("===== 输入：{} 个不同 requestId 并发申请，owner=bob 上限=3 =====", threads);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ok.add(harness.service.apply(req("race-" + idx, "bob", "teamA")));
                } catch (BusinessException ex) {
                    rejected.add(ex.getCode());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(3, ok.size(), "判定依据：成功数必须精确等于 owner 配额 3");
        assertEquals(threads - 3, rejected.size(), "判定依据：其余全部被拒绝");
        assertTrue(rejected.stream().allMatch(c -> c == ErrorCode.OWNER_QUOTA_EXCEEDED),
                "判定依据：拒绝原因必须一致且可区分，实际=" + new HashSet<>(rejected));
        assertEquals(3L, harness.ledger.ownerView("bob").used(),
                "判定依据：账本占用绝不超过上限（不超卖）");
        assertEquals(3, harness.provisioner.getSuccessCount(),
                "判定依据：资源分配次数=成功申请数，被拒申请不分配");
        log.info("判定结果：并发 {} 请求 -> 成功 {} 被拒 {}（全部 OWNER_QUOTA_EXCEEDED）used={}",
                threads, ok.size(), rejected.size(), harness.ledger.ownerView("bob").used());
    }

    @Test
    @DisplayName("串行重复提交同一 requestId 也返回同一租约（幂等不依赖并发）")
    void duplicateRequestSerial() {
        LeaseView v1 = harness.service.apply(req("serial-1", "carol", "teamA"));
        LeaseView v2 = harness.service.apply(req("serial-1", "carol", "teamA"));
        assertEquals(v1.id(), v2.id());
        assertEquals(1, harness.provisioner.getSuccessCount());
        log.info("串行重复提交复用 leaseId={} 资源分配次数={}", v2.id(),
                harness.provisioner.getSuccessCount());
    }
}
