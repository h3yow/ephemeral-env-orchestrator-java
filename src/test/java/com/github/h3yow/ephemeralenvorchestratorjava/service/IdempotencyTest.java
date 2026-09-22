package com.github.h3yow.ephemeralenvorchestratorjava.service;

import com.github.h3yow.ephemeralenvorchestratorjava.domain.OrchestrationException;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.RejectionReason;
import com.github.h3yow.ephemeralenvorchestratorjava.provision.LocalResourceProvisioner;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等测试：重复提交同一申请不得重复分配资源/占用配额；
 * 同一幂等键搭配不同归属必须以 IDEMPOTENCY_CONFLICT 拒绝。
 */
class IdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyTest.class);

    private final LocalResourceProvisioner provisioner = TestSupport.provisioner(10);
    private final EnvironmentOrchestrator orchestrator =
            new EnvironmentOrchestrator(TestSupport.defaults(),
                    TestSupport.controlledClock(new AtomicLong(1_000_000L)), provisioner);

    @Test
    void duplicateRequestIdReturnsSameEnvWithoutDoubleAllocation() {
        var first = orchestrator.apply(new Commands.ApplyCommand("dup-1", "alice", null));
        var second = orchestrator.apply(new Commands.ApplyCommand("dup-1", "alice", null));
        var third = orchestrator.apply(new Commands.ApplyCommand("dup-1", "alice", "alice"));

        log.info("[重复提交] 输入: 同一 requestId=dup-1 提交3次, 判定: envId 一致={}, 首次duplicate={}, 后续duplicate={}/{}, 资源占用={}",
                first.envId().equals(second.envId()) && first.envId().equals(third.envId()),
                first.duplicate(), second.duplicate(), third.duplicate(), provisioner.allocatedCount());

        assertEquals(first.envId(), second.envId());
        assertEquals(first.envId(), third.envId());
        assertFalse(first.duplicate());
        assertTrue(second.duplicate());
        assertTrue(third.duplicate());
        assertEquals(1, provisioner.allocatedCount(), "重复提交不得重复分配资源");
        assertEquals(1, orchestrator.ownerQuota("alice").used(), "重复提交不得重复占用配额");
    }

    @Test
    void sameRequestIdWithDifferentOwnerIsConflict() {
        orchestrator.apply(new Commands.ApplyCommand("dup-2", "alice", "team-a"));
        OrchestrationException ex = assertThrows(OrchestrationException.class,
                () -> orchestrator.apply(new Commands.ApplyCommand("dup-2", "bob", "team-a")));
        log.info("[幂等冲突] 输入: requestId=dup-2 先(alice,team-a) 后(bob,team-a), 判定: reason={}, message={}",
                ex.getReason(), ex.getMessage());
        assertEquals(RejectionReason.IDEMPOTENCY_CONFLICT, ex.getReason());
        assertEquals(1, provisioner.allocatedCount(), "冲突请求不得额外分配");
    }
}
