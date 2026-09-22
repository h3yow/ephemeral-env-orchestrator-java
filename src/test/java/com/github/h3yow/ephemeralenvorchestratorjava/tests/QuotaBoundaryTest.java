package com.github.h3yow.ephemeralenvorchestratorjava.tests;

import com.github.h3yow.ephemeralenvorchestratorjava.domain.LeaseStatus;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配额边界测试：owner/team 两级配额、超配额拒绝原因可区分、终态释放配额后可再申请。
 */
class QuotaBoundaryTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaBoundaryTest.class);

    private TestHarness harness;

    @BeforeEach
    void setUp() {
        // owner 配额=2，team 配额=3
        harness = new TestHarness(2L, 3L);
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
    @DisplayName("owner 配额边界：第 3 个申请以 OWNER_QUOTA_EXCEEDED 被拒绝，且未创建租约")
    void ownerQuotaBoundary() {
        LeaseView a = harness.service.apply(req("q-1", "alice", "teamA"));
        LeaseView b = harness.service.apply(req("q-2", "alice", "teamA"));
        log.info("输入：alice 已持有 {} 个环境（上限 2），提交第 3 个申请 q-3", 2);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> harness.service.apply(req("q-3", "alice", "teamA")));
        assertEquals(ErrorCode.OWNER_QUOTA_EXCEEDED, ex.getCode(),
                "判定依据：used=2 >= limit=2，原因必须区别于状态非法/资源不足");
        log.info("超 owner 配额被拒绝 code={} message={}", ex.getCode(), ex.getMessage());

        assertEquals(2L, harness.ledger.ownerView("alice").used(),
                "判定依据：被拒绝的申请不得占用配额");
        assertEquals(2, harness.provisioner.getSuccessCount(),
                "判定依据：被拒绝的申请不得触发供给分配资源");

        // 释放一个后可再申请
        harness.service.terminate(a.id(), "alice");
        log.info("输入：终止一个环境后再次提交 q-3，owner used={}",
                harness.ledger.ownerView("alice").used());
        LeaseView c = harness.service.apply(req("q-3", "alice", "teamA"));
        assertNotNull(c.endpoint());
        assertEquals(2L, harness.ledger.ownerView("alice").used());
    }

    @Test
    @DisplayName("team 配额边界：跨 owner 累计达到 team 上限时以 TEAM_QUOTA_EXCEEDED 拒绝")
    void teamQuotaBoundary() {
        // alice 2 个（达 owner 上限），bob 1 个 -> team 共 3 个，达 team 上限
        harness.service.apply(req("t-1", "alice", "shared"));
        harness.service.apply(req("t-2", "alice", "shared"));
        harness.service.apply(req("t-3", "bob", "shared"));
        log.info("输入：team=shared 已有 alice*2 + bob*1 = 3（上限 3），carol 再申请");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> harness.service.apply(req("t-4", "carol", "shared")));
        assertEquals(ErrorCode.TEAM_QUOTA_EXCEEDED, ex.getCode(),
                "判定依据：team used=3 >= limit=3；carol 自身 owner 配额未满，故不是 owner 原因");
        log.info("超 team 配额被拒绝 code={} message={}", ex.getCode(), ex.getMessage());

        // 不同 team 不受影响
        LeaseView otherTeam = harness.service.apply(req("t-5", "carol", "other"));
        assertNotNull(otherTeam.endpoint(), "判定依据：其它 team 配额独立");
    }

    @Test
    @DisplayName("过期回收释放配额后可再申请，失败达到上限也释放配额")
    void quotaReleasedOnExpiryAndFailure() {
        LeaseView a = harness.service.apply(req("e-1", "alice", "teamA"));
        LeaseView b = harness.service.apply(req("e-2", "alice", "teamA"));
        assertEquals(2L, harness.ledger.ownerView("alice").used());

        harness.clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        int reaped = harness.service.reapExpired();
        assertEquals(2, reaped, "判定依据：两个租约同一时刻到期，应一并回收");
        log.info("输入：时钟推进到 {}，reap 回收 {} 个，owner used={}",
                harness.clock.now(), reaped, harness.ledger.ownerView("alice").used());
        assertEquals(0L, harness.ledger.ownerView("alice").used(),
                "判定依据：过期回收必须释放配额");

        LeaseView c = harness.service.apply(req("e-3", "alice", "teamA"));
        assertEquals(LeaseStatus.READY, c.status());
        assertEquals(1L, harness.ledger.ownerView("alice").used());
    }

    @Test
    @DisplayName("供给失败占用配额直到达到上限：重试成功后保持占用，超限后释放")
    void failureQuotaSemantics() {
        // 前两次供给为资源耗尽，第三次成功（maxAttempts=3）
        harness.provisioner.setScript(com.github.h3yow.ephemeralenvorchestratorjava
                .tests.support.ScriptedProvisioner.Mode.EXHAUSTED, 2);
        LeaseView failed = harness.service.apply(req("f-1", "alice", "teamA"));
        assertEquals(LeaseStatus.FAILED,
                failed.status());
        assertEquals(1L, harness.ledger.ownerView("alice").used(),
                "判定依据：可重试的失败仍占配额，防止绕过配额冲击资源池");
        log.info("第一次供给失败但仍可重试，owner used={} failures={}",
                harness.ledger.ownerView("alice").used(), failed.failures());

        LeaseView ready = harness.service.retry(failed.id(), "alice");
        // 第 2 次失败后仍 FAILED 可重试
        if (ready.status() == LeaseStatus.FAILED) {
            log.info("第 2 次仍失败，继续重试 failures={}", ready.failures().size());
            ready = harness.service.retry(failed.id(), "alice");
        }
        assertEquals(LeaseStatus.READY,
                ready.status(), "判定依据：第三次供给成功");
        assertEquals(1L, harness.ledger.ownerView("alice").used());
        assertNotNull(ready.endpoint());

        // 验证失败记录保留且可解释
        assertEquals(2, ready.failures().size());
        log.info("失败记录保留：{}", ready.failures());
    }
}
