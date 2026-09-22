package com.github.h3yow.ephemeralenvorchestratorjava.service;

import com.github.h3yow.ephemeralenvorchestratorjava.domain.OrchestrationException;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.RejectionReason;
import com.github.h3yow.ephemeralenvorchestratorjava.provision.LocalResourceProvisioner;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 配额边界测试：所有者配额、团队配额与资源池容量三类上限必须分别触发、原因可区分；
 * 终止释放配额后可再次申请。
 */
class QuotaTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaTest.class);

    private EnvironmentOrchestrator orchestrator(int ownerQuota, int teamQuota, int poolSize) {
        return new EnvironmentOrchestrator(
                TestSupport.props(ownerQuota, teamQuota, 300, 300, 3, poolSize),
                TestSupport.controlledClock(new AtomicLong(1_000_000L)),
                TestSupport.provisioner(poolSize));
    }

    @Test
    void ownerQuotaBoundaryRejectsExactlyAtLimit() {
        var orch = orchestrator(2, 100, 100);
        orch.apply(new Commands.ApplyCommand("o1", "alice", null));
        orch.apply(new Commands.ApplyCommand("o2", "alice", null));
        OrchestrationException ex = assertThrows(OrchestrationException.class,
                () -> orch.apply(new Commands.ApplyCommand("o3", "alice", null)));
        log.info("[所有者配额边界] 输入: ownerQuota=2, 第3次申请, 判定: reason={}, message={}",
                ex.getReason(), ex.getMessage());
        assertEquals(RejectionReason.OWNER_QUOTA_EXCEEDED, ex.getReason());
        assertEquals(2, orch.ownerQuota("alice").used());

        // 终止一个释放配额后应可再申请
        String envId = orch.getByRequestId("o1").envId();
        orch.terminate(new Commands.TerminateCommand(envId, "alice"));
        var retry = orch.apply(new Commands.ApplyCommand("o3", "alice", null));
        log.info("[配额释放] 输入: 终止 o1 后再申请 o3, 判定: 成功 envId={}, used={}",
                retry.envId(), orch.ownerQuota("alice").used());
        assertEquals(2, orch.ownerQuota("alice").used());
    }

    @Test
    void teamQuotaBoundaryIsDistinguishedFromOwnerQuota() {
        var orch = orchestrator(100, 2, 100);
        orch.apply(new Commands.ApplyCommand("t1", "alice", "team-x"));
        orch.apply(new Commands.ApplyCommand("t2", "bob", "team-x"));
        OrchestrationException ex = assertThrows(OrchestrationException.class,
                () -> orch.apply(new Commands.ApplyCommand("t3", "carol", "team-x")));
        log.info("[团队配额边界] 输入: teamQuota=2(alice,bob), carol 第3个, 判定: reason={}, message={}",
                ex.getReason(), ex.getMessage());
        assertEquals(RejectionReason.TEAM_QUOTA_EXCEEDED, ex.getReason());
        assertEquals(2, orch.teamQuota("team-x").used());
        assertEquals(0, orch.ownerQuota("carol").used(), "被拒申请不得占用 carol 的所有者配额");
    }

    @Test
    void resourcePoolExhaustionIsDistinguishedFromQuota() {
        // 配额很大但资源池只有 1：资源不足必须与配额原因区分（供给失败 RESOURCE_SHORTAGE → FAILED）
        var provisioner = TestSupport.provisioner(1);
        var orch = new EnvironmentOrchestrator(
                TestSupport.props(100, 100, 300, 300, 3, 1),
                TestSupport.controlledClock(new AtomicLong(1_000_000L)), provisioner);
        orch.apply(new Commands.ApplyCommand("p1", "alice", null));
        var second = orch.apply(new Commands.ApplyCommand("p2", "bob", null));
        log.info("[资源不足] 输入: poolSize=1, 第二个申请, 判定: 状态={}, 失败记录={}, 资源占用={}",
                second.view().status(),
                second.view().failures().stream().map(f -> f.reasonCode()).toList(),
                provisioner.allocatedCount());
        // 容量不足表现为可解释的供给失败，且不占用配额（used=0）
        assertEquals(com.github.h3yow.ephemeralenvorchestratorjava.domain.EnvironmentStatus.FAILED,
                second.view().status());
        assertEquals("RESOURCE_SHORTAGE", second.view().failures().get(0).reasonCode());
        assertEquals(0, orch.ownerQuota("bob").used(), "供给失败不得占用配额");
        assertEquals(1, provisioner.allocatedCount(), "失败归还占位，池中仍只被第一个占用");

        // 释放资源后重试第二个环境应当成功
        orch.terminate(new Commands.TerminateCommand(orch.getByRequestId("p1").envId(), "alice"));
        var retried = orch.retry(new Commands.RetryCommand(second.envId(), "bob"));
        log.info("[资源恢复后重试] 输入: 释放 p1 后 retry p2, 判定: 状态={}, resourceId={}",
                retried.status(), retried.resourceId());
        assertEquals(com.github.h3yow.ephemeralenvorchestratorjava.domain.EnvironmentStatus.READY,
                retried.status());
    }
}
