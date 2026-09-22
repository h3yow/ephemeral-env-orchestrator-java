package com.github.h3yow.ephemeralenvorchestratorjava.service;

import com.github.h3yow.ephemeralenvorchestratorjava.clock.SafeClock;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.EnvironmentStatus;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.OrchestrationException;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.RejectionReason;
import com.github.h3yow.ephemeralenvorchestratorjava.provision.LocalResourceProvisioner;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 过期判定测试：
 * 1) 到期扫描回收并释放配额；2) 续租已到期环境被拒绝且原因是 LEASE_EXPIRED；
 * 3) 系统时间回拨不得提前回收有效租约，也不得延长已结束租约。
 */
class LeaseExpiryTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseExpiryTest.class);

    private static final long T0 = 1_000_000_000L;

    private EnvironmentOrchestrator orchestrator(AtomicLong clockSource, long ttlSeconds, long extendSeconds) {
        return new EnvironmentOrchestrator(
                TestSupport.props(5, 20, ttlSeconds, extendSeconds, 3, 20),
                new SafeClock(clockSource::get), TestSupport.provisioner(20));
    }

    @Test
    void sweepReclaimsExpiredLeasesAndReleasesQuota() {
        var source = new AtomicLong(T0);
        var orch = orchestrator(source, 100, 100);
        var r = orch.apply(new Commands.ApplyCommand("e1", "alice", null));
        Instant expiresAt = r.view().expiresAt();
        log.info("[到期回收] 输入: ttl=100s, expiresAt={}, 判定依据: now 到达 expiresAt 即到期", expiresAt);

        source.set(T0 + 50_000);
        assertEquals(0, orch.sweepExpired(), "未到期不得回收");
        assertEquals(EnvironmentStatus.READY, orch.get(r.envId()).status());

        source.set(T0 + 100_000);
        int reclaimed = orch.sweepExpired();
        log.info("[到期回收] 输入: now={} >= expiresAt={}, 判定: 回收数量={}, 状态={}, ownerQuotaUsed={}",
                Instant.ofEpochMilli(T0 + 100_000), expiresAt, reclaimed,
                orch.get(r.envId()).status(), orch.ownerQuota("alice").used());
        assertEquals(1, reclaimed);
        assertEquals(EnvironmentStatus.EXPIRED, orch.get(r.envId()).status());
        assertEquals(0, orch.ownerQuota("alice").used(), "回收后配额必须释放");

        // 已结束（EXPIRED）环境不得再终止
        OrchestrationException ex = assertThrows(OrchestrationException.class,
                () -> orch.terminate(new Commands.TerminateCommand(r.envId(), "alice")));
        assertEquals(RejectionReason.ILLEGAL_STATE_TRANSITION, ex.getReason());
    }

    @Test
    void renewBeforeExpiryExtendsLease() {
        var source = new AtomicLong(T0);
        var orch = orchestrator(source, 100, 100);
        var r = orch.apply(new Commands.ApplyCommand("e2", "alice", null));

        source.set(T0 + 50_000);
        var renewed = orch.renew(new Commands.RenewCommand(r.envId(), "alice"));
        log.info("[续租] 输入: now=T0+50s, 判定: 新 expiresAt={} (应=T0+150s), renewedCount={}",
                renewed.expiresAt(), renewed.renewedCount());
        assertEquals(Instant.ofEpochMilli(T0 + 150_000), renewed.expiresAt());
        assertEquals(1, renewed.renewedCount());

        source.set(T0 + 120_000);
        assertEquals(0, orch.sweepExpired(), "续租后在原到期时间点仍应有效");
        assertEquals(EnvironmentStatus.READY, orch.get(r.envId()).status());
    }

    @Test
    void renewAfterExpiryIsRejectedAndLeaseReclaimedExactlyOnce() {
        var source = new AtomicLong(T0);
        var provisioner = TestSupport.provisioner(20);
        var orch = new EnvironmentOrchestrator(
                TestSupport.props(5, 20, 100, 100, 3, 20),
                new SafeClock(source::get), provisioner);
        var r = orch.apply(new Commands.ApplyCommand("e3", "alice", null));

        source.set(T0 + 100_000);
        OrchestrationException ex = assertThrows(OrchestrationException.class,
                () -> orch.renew(new Commands.RenewCommand(r.envId(), "alice")));
        log.info("[续租过期竞态] 输入: 到期时刻续租 envId={}, 判定: reason={}", r.envId(), ex.getReason());
        assertEquals(RejectionReason.LEASE_EXPIRED, ex.getReason());

        var view = orch.get(r.envId());
        assertEquals(EnvironmentStatus.EXPIRED, view.status());
        assertEquals(0, orch.ownerQuota("alice").used());
        assertEquals(0, provisioner.allocatedCount(), "回收只释放一次资源");

        // 时间继续前进后再次续租，依旧是非法状态，绝不能“复活”已结束租约
        source.set(T0 + 200_000);
        OrchestrationException again = assertThrows(OrchestrationException.class,
                () -> orch.renew(new Commands.RenewCommand(r.envId(), "alice")));
        log.info("[终态续租] 输入: 已 EXPIRED 环境再次续租, 判定: reason={}", again.getReason());
        assertEquals(RejectionReason.ILLEGAL_STATE_TRANSITION, again.getReason());
    }

    @Test
    void wallClockRollbackDoesNotReclaimValidLease() {
        var source = new AtomicLong(T0);
        var orch = orchestrator(source, 100, 100);
        var r = orch.apply(new Commands.ApplyCommand("e4", "alice", null));
        Instant originalExpiry = r.view().expiresAt();

        // 先前进到 T0+80s（未到期），再回拨到 T0+10s
        source.set(T0 + 80_000);
        orch.get(r.envId());
        source.set(T0 + 10_000);
        int reclaimed = orch.sweepExpired();
        log.info("[时间回拨] 输入: 先 T0+80s 再回拨 T0+10s, ttl=100s, 判定: 回收数量={}（安全时钟停在 T0+80s，仍未到期）, 状态={}",
                reclaimed, orch.get(r.envId()).status());
        assertEquals(0, reclaimed, "回拨不得影响有效期：安全时钟未倒退，租约仍 READY");
        assertEquals(EnvironmentStatus.READY, orch.get(r.envId()).status());
        assertEquals(originalExpiry, orch.get(r.envId()).expiresAt(), "回拨不得改变到期时刻");
    }
}
