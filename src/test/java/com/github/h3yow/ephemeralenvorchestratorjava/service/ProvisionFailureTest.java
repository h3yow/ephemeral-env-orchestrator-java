package com.github.h3yow.ephemeralenvorchestratorjava.service;

import com.github.h3yow.ephemeralenvorchestratorjava.clock.SafeClock;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.EnvironmentStatus;
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
 * 供给失败测试：失败记录必须保留、失败不长期占用配额、达到上限后以
 * RETRY_LIMIT_EXHAUSTED 明确拒绝（绝不静默成功），成功重试后环境恢复 READY。
 */
class ProvisionFailureTest {

    private static final Logger log = LoggerFactory.getLogger(ProvisionFailureTest.class);

    private static final long T0 = 2_000_000L;

    @Test
    void failedProvisionKeepsRecordsReleasesQuotaAndSucceedsOnRetry() {
        var provisioner = TestSupport.provisioner(10);
        // 该环境前两次供给注入失败
        provisioner.setFailureInjection((envId, attempt) -> attempt < 3);
        var orch = new EnvironmentOrchestrator(
                TestSupport.props(2, 10, 300, 300, 3, 10),
                new SafeClock(new AtomicLong(T0)::get), provisioner);

        var first = orch.apply(new Commands.ApplyCommand("f1", "alice", null));
        log.info("[供给失败] 输入: apply f1(第1次注入失败), 判定: 状态={}, failures={}, ownerUsed={}, allocated={}",
                first.view().status(),
                first.view().failures().stream().map(f -> f.attempt() + ":" + f.reasonCode()).toList(),
                orch.ownerQuota("alice").used(), provisioner.allocatedCount());
        assertEquals(EnvironmentStatus.FAILED, first.view().status());
        assertEquals(1, first.view().failures().size());
        assertEquals(0, orch.ownerQuota("alice").used(), "失败不得占用配额");
        assertEquals(0, provisioner.allocatedCount(), "失败不得占用资源");

        var second = orch.retry(new Commands.RetryCommand(first.envId(), "alice"));
        log.info("[第二次仍失败] 判定: 状态={}, 失败次数={}, ownerUsed={}",
                second.status(), second.failures().size(), orch.ownerQuota("alice").used());
        assertEquals(EnvironmentStatus.FAILED, second.status());
        assertEquals(2, second.failures().size(), "失败历史必须累积保留");
        assertEquals(0, orch.ownerQuota("alice").used());

        var third = orch.retry(new Commands.RetryCommand(first.envId(), "alice"));
        log.info("[第三次成功] 判定: 状态={}, resourceId={}, 失败记录仍保留={}条, ownerUsed={}",
                third.status(), third.resourceId(), third.failures().size(), orch.ownerQuota("alice").used());
        assertEquals(EnvironmentStatus.READY, third.status());
        assertEquals(2, third.failures().size(), "成功后历史失败记录仍须保留可解释");
        assertEquals(1, orch.ownerQuota("alice").used());
    }

    @Test
    void retryLimitExhaustedIsRejectedNotSilentlySucceeded() {
        var provisioner = TestSupport.provisioner(10);
        provisioner.setFailureInjection((envId, attempt) -> true); // 永远失败
        var orch = new EnvironmentOrchestrator(
                TestSupport.props(2, 10, 300, 300, 2, 10),
                new SafeClock(new AtomicLong(T0)::get), provisioner);

        var first = orch.apply(new Commands.ApplyCommand("f2", "alice", null));
        var second = orch.retry(new Commands.RetryCommand(first.envId(), "alice"));
        log.info("[重试上限] 输入: maxAttempts=2 全部注入失败, 判定: 状态={}, terminalMessage={}",
                second.status(), second.terminalMessage());
        assertEquals(EnvironmentStatus.FAILED, second.status());
        assertEquals(2, second.failures().size());
        assertEquals(0, orch.ownerQuota("alice").used());

        OrchestrationException ex = assertThrows(OrchestrationException.class,
                () -> orch.retry(new Commands.RetryCommand(first.envId(), "alice")));
        log.info("[重试耗尽拒绝] 输入: 第3次 retry, 判定: reason={}, message={}", ex.getReason(), ex.getMessage());
        assertEquals(RejectionReason.RETRY_LIMIT_EXHAUSTED, ex.getReason());
        assertEquals(0, provisioner.allocatedCount(), "耗尽后资源不被占用");

        // 终态 FAILED 也不能再终止/续租
        assertThrows(OrchestrationException.class,
                () -> orch.terminate(new Commands.TerminateCommand(first.envId(), "alice")));
        assertThrows(OrchestrationException.class,
                () -> orch.renew(new Commands.RenewCommand(first.envId(), "alice")));
    }

    @Test
    void retryOnReadyEnvIsIllegalTransitionNotLimitError() {
        var provisioner = TestSupport.provisioner(10);
        var orch = new EnvironmentOrchestrator(
                TestSupport.defaults(), new SafeClock(new AtomicLong(T0)::get), provisioner);
        var r = orch.apply(new Commands.ApplyCommand("f3", "alice", null));
        OrchestrationException ex = assertThrows(OrchestrationException.class,
                () -> orch.retry(new Commands.RetryCommand(r.envId(), "alice")));
        log.info("[重试非法状态] 输入: 对 READY 环境 retry, 判定: reason={}", ex.getReason());
        assertEquals(RejectionReason.ILLEGAL_STATE_TRANSITION, ex.getReason());
    }
}
