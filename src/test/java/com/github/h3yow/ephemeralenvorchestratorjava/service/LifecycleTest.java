package com.github.h3yow.ephemeralenvorchestratorjava.service;

import com.github.h3yow.ephemeralenvorchestratorjava.domain.EnvironmentStatus;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.OrchestrationException;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.RejectionReason;
import com.github.h3yow.ephemeralenvorchestratorjava.provision.LocalResourceProvisioner;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生命周期测试：申请即就绪、主动终止、终态后非法流转必须被拒绝，
 * 且 ILLEGAL_STATE_TRANSITION 与 NOT_OWNER 等原因互不混淆。
 */
class LifecycleTest {

    private static final Logger log = LoggerFactory.getLogger(LifecycleTest.class);

    private final LocalResourceProvisioner provisioner = TestSupport.provisioner(5);
    private final EnvironmentOrchestrator orchestrator =
            new EnvironmentOrchestrator(TestSupport.defaults(),
                    TestSupport.controlledClock(new java.util.concurrent.atomic.AtomicLong(1_000_000L)),
                    provisioner);

    @Test
    void applyThenTerminateCompletesLifecycle() {
        var result = orchestrator.apply(new Commands.ApplyCommand("req-1", "alice", null));
        log.info("[生命周期] 输入: apply(req-1, alice) -> envId={}, 判定: 状态必须 READY",
                result.envId());
        assertEquals(EnvironmentStatus.READY, result.view().status());
        assertNotNull(result.view().resourceId());
        assertEquals(1, provisioner.allocatedCount());

        var terminated = orchestrator.terminate(new Commands.TerminateCommand(result.envId(), "alice"));
        log.info("[生命周期] 输入: terminate({}, alice), 判定: 状态={}, 资源释放后占用={}",
                result.envId(), terminated.status(), provisioner.allocatedCount());
        assertEquals(EnvironmentStatus.TERMINATED, terminated.status());
        assertEquals(0, provisioner.allocatedCount(), "终止后资源必须释放");

        // 重复终止：幂等无操作
        var again = orchestrator.terminate(new Commands.TerminateCommand(result.envId(), "alice"));
        assertEquals(EnvironmentStatus.TERMINATED, again.status());
        assertEquals(0, provisioner.allocatedCount(), "重复终止不得重复释放");
    }

    @Test
    void renewAfterTerminateIsRejectedAsIllegalTransition() {
        var result = orchestrator.apply(new Commands.ApplyCommand("req-2", "alice", null));
        orchestrator.terminate(new Commands.TerminateCommand(result.envId(), "alice"));

        OrchestrationException ex = assertThrows(OrchestrationException.class,
                () -> orchestrator.renew(new Commands.RenewCommand(result.envId(), "alice")));
        log.info("[非法流转] 输入: renew 已终止环境 {}, 判定: reason={}, message={}",
                result.envId(), ex.getReason(), ex.getMessage());
        assertEquals(RejectionReason.ILLEGAL_STATE_TRANSITION, ex.getReason());
    }

    @Test
    void crossOwnerOperationsAreRejectedAndDistinguishedFromIllegalState() {
        var result = orchestrator.apply(new Commands.ApplyCommand("req-3", "alice", "team-a"));

        OrchestrationException ex = assertThrows(OrchestrationException.class,
                () -> orchestrator.terminate(new Commands.TerminateCommand(result.envId(), "bob")));
        log.info("[归属校验] 输入: terminate({}, bob) 实际所有者 alice, 判定: reason={}",
                result.envId(), ex.getReason());
        assertEquals(RejectionReason.NOT_OWNER, ex.getReason());

        OrchestrationException blank = assertThrows(OrchestrationException.class,
                () -> orchestrator.apply(new Commands.ApplyCommand("req-x", "  ", null)));
        log.info("[参数校验] 输入: owner 为空白, 判定: reason={}", blank.getReason());
        assertEquals(RejectionReason.INVALID_REQUEST, blank.getReason());
        assertTrue(provisioner.allocatedCount() >= 1);
    }
}
