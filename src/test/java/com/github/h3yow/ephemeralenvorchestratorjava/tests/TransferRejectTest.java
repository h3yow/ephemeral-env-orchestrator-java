package com.github.h3yow.ephemeralenvorchestratorjava.tests;

import com.github.h3yow.ephemeralenvorchestratorjava.domain.LeaseStatus;
import com.github.h3yow.ephemeralenvorchestratorjava.error.BusinessException;
import com.github.h3yow.ephemeralenvorchestratorjava.error.ErrorCode;
import com.github.h3yow.ephemeralenvorchestratorjava.tests.support.ScriptedProvisioner;
import com.github.h3yow.ephemeralenvorchestratorjava.tests.support.TestHarness;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.ApplyLeaseRequest;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.LeaseView;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.OperationRequest;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.TransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 转让拒绝原因必须彼此可区分，且任何拒绝都不得改动归属、状态与配额（两边都不动）：
 * NOT_OWNER / OWNER_QUOTA_EXCEEDED / TEAM_QUOTA_EXCEEDED /
 * LEASE_NOT_TRANSFERABLE / LEASE_ALREADY_TERMINATED / LEASE_ALREADY_EXPIRED /
 * LEASE_NOT_FOUND / VALIDATION_ERROR。
 */
class TransferRejectTest {

    private static final Logger log = LoggerFactory.getLogger(TransferRejectTest.class);

    private TestHarness harness;

    @BeforeEach
    void setUp() {
        // owner 配额=2、team 配额=2，便于把目标 owner 与目标 team 分别填满
        harness = new TestHarness(2L, 2L);
    }

    private LeaseView apply(String requestId, String owner, String team) {
        ApplyLeaseRequest r = new ApplyLeaseRequest();
        r.setRequestId(requestId);
        r.setOwner(owner);
        r.setTeam(team);
        r.setTtl("PT10M");
        return harness.service.apply(r);
    }

    private TransferRequest transfer(String requestId, String operator, String owner, String team) {
        TransferRequest r = new TransferRequest();
        r.setRequestId(requestId);
        r.setOperator(operator);
        r.setTargetOwner(owner);
        r.setTargetTeam(team);
        return r;
    }

    private void assertReject(Runnable action, ErrorCode expected, String name) {
        BusinessException ex = assertThrows(BusinessException.class, action::run);
        assertEquals(expected, ex.getCode(),
                "判定依据：" + name + " 必须以 " + expected + " 拒绝，实际=" + ex.getCode());
        log.info("{} -> {}（{}）", name, ex.getCode(), ex.getMessage());
    }

    @Test
    @DisplayName("非当前归属者发起转让：NOT_OWNER；转让后原归属者再发起也是 NOT_OWNER")
    void rejectWhenNotOwner() {
        LeaseView env = apply("apply-1", "alice", "teamA");
        assertReject(() -> harness.service.transfer(env.id(), transfer("tr-x1", "mallory", "bob", "teamB")),
                ErrorCode.NOT_OWNER, "陌生人发起转让");
        assertEquals(1L, harness.ledger.ownerView("alice").used());
        assertEquals(0L, harness.ledger.ownerView("bob").used());

        harness.service.transfer(env.id(), transfer("tr-ok", "alice", "bob", "teamB"));
        assertReject(() -> harness.service.transfer(env.id(), transfer("tr-x2", "alice", "carol", "teamC")),
                ErrorCode.NOT_OWNER, "原归属者在易主后再发起转让");
        assertEquals("bob", harness.service.get(env.id()).owner(), "判定依据：被拒转让不得改动归属");
    }

    @Test
    @DisplayName("目标 owner 配额已满：OWNER_QUOTA_EXCEEDED，且源那边不释放、目标那边不占用")
    void rejectWhenTargetOwnerQuotaFull() {
        LeaseView env = apply("apply-2a", "alice", "teamA");
        apply("apply-2b", "bob", "teamB");
        apply("apply-2c", "bob", "teamB");
        assertEquals(2L, harness.ledger.ownerView("bob").used(), "前置：bob 已到 owner 上限 2");

        assertReject(() -> harness.service.transfer(env.id(), transfer("tr-full-o", "alice", "bob", "teamX")),
                ErrorCode.OWNER_QUOTA_EXCEEDED, "目标 owner 已满");

        assertEquals(1L, harness.ledger.ownerView("alice").used(), "判定依据：源配额未被释放（无半更新）");
        assertEquals(2L, harness.ledger.ownerView("bob").used(), "判定依据：目标未被占用（无半更新）");
        assertEquals(0L, harness.ledger.teamView("teamX").used());
        assertEquals("alice", harness.service.get(env.id()).owner());
        assertEquals("teamA", harness.service.get(env.id()).team());
    }

    @Test
    @DisplayName("目标 team 配额已满（owner 仍有空位）：TEAM_QUOTA_EXCEEDED，水位两边不动")
    void rejectWhenTargetTeamQuotaFull() {
        LeaseView env = apply("apply-3a", "alice", "teamA");
        apply("apply-3b", "u1", "fullTeam");
        apply("apply-3c", "u2", "fullTeam");

        assertReject(() -> harness.service.transfer(env.id(),
                        transfer("tr-full-t", "alice", "carol", "fullTeam")),
                ErrorCode.TEAM_QUOTA_EXCEEDED, "目标 team 已满");

        assertEquals(1L, harness.ledger.ownerView("alice").used());
        assertEquals(0L, harness.ledger.ownerView("carol").used());
        assertEquals(1L, harness.ledger.teamView("teamA").used());
        assertEquals(2L, harness.ledger.teamView("fullTeam").used(), "判定依据：目标 team 水位不增加");
    }

    @Test
    @DisplayName("owner 与 team 同时满时先报 owner；owner 有空位而 team 满时报 team——两级原因可区分")
    void quotaReasonOrdering() {
        LeaseView env = apply("apply-4", "alice", "teamA");
        apply("apply-4b", "bob", "fullTeam");
        apply("apply-4c", "bob", "fullTeam");
        // bob 的 owner 配额=2 已满，fullTeam 的 team 配额=2 也满
        assertReject(() -> harness.service.transfer(env.id(),
                        transfer("tr-both", "alice", "bob", "fullTeam")),
                ErrorCode.OWNER_QUOTA_EXCEEDED, "owner/team 双满先报 owner");
    }

    @Test
    @DisplayName("非 READY 状态（供给失败 FAILED）转让：LEASE_NOT_TRANSFERABLE，配额与归属不变")
    void rejectWhenNotReady() {
        harness.provisioner.setScript(ScriptedProvisioner.Mode.FAIL, 1);
        LeaseView failed = apply("apply-5", "alice", "teamA");
        assertEquals(LeaseStatus.FAILED, failed.status());

        assertReject(() -> harness.service.transfer(failed.id(), transfer("tr-failed", "alice", "bob", "teamB")),
                ErrorCode.LEASE_NOT_TRANSFERABLE, "FAILED 环境转让");
        assertEquals(1L, harness.ledger.ownerView("alice").used(), "判定依据：FAILED 仍占源配额且不搬运");
        assertEquals(0L, harness.ledger.ownerView("bob").used());
    }

    @Test
    @DisplayName("已终止环境不能转让：LEASE_ALREADY_TERMINATED，配额保持已释放状态不被搬运")
    void rejectWhenTerminated() {
        LeaseView env = apply("apply-6", "alice", "teamA");
        harness.service.terminate(env.id(), "alice");
        OperationRequest ignored = new OperationRequest();

        assertReject(() -> harness.service.transfer(env.id(), transfer("tr-term", "alice", "bob", "teamB")),
                ErrorCode.LEASE_ALREADY_TERMINATED, "TERMINATED 环境转让");
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(0L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.teamView("teamB").used(),
                "判定依据：已释放的配额绝不能被一次转让重新挂到目标头上（配额不被吞）");
        assertEquals(LeaseStatus.TERMINATED, harness.service.get(env.id()).status());
    }

    @Test
    @DisplayName("已到期回收环境不能转让：LEASE_ALREADY_EXPIRED；恰好在到期点发起也被原子回收拒绝")
    void rejectWhenExpired() {
        LeaseView env = apply("apply-7", "alice", "teamA");
        harness.clock.set(Instant.parse("2026-09-22T00:10:00Z"));
        // 先让系统观测到该时刻（查询触发惰性回收）
        assertEquals(LeaseStatus.EXPIRED, harness.service.get(env.id()).status());

        assertReject(() -> harness.service.transfer(env.id(), transfer("tr-exp1", "alice", "bob", "teamB")),
                ErrorCode.LEASE_ALREADY_EXPIRED, "EXPIRED 环境转让");

        LeaseView env2 = apply("apply-7b", "alice", "teamA");
        harness.clock.set(Instant.parse("2026-09-22T00:20:00Z")); // env2 expiresAt=00:20，恰好在边界
        assertReject(() -> harness.service.transfer(env2.id(), transfer("tr-exp2", "alice", "bob", "teamB")),
                ErrorCode.LEASE_ALREADY_EXPIRED, "到期边界上的转让");
        assertEquals(LeaseStatus.EXPIRED, harness.service.get(env2.id()).status(),
                "判定依据：边界转让原子完成回收，不会把已结束环境转走");
        assertEquals(0L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.teamView("teamB").used());
    }

    @Test
    @DisplayName("不存在的环境：LEASE_NOT_FOUND；空转让（目标与当前完全相同）：VALIDATION_ERROR")
    void rejectNotFoundAndNoOp() {
        assertReject(() -> harness.service.transfer("nope", transfer("tr-missing", "alice", "bob", "teamB")),
                ErrorCode.LEASE_NOT_FOUND, "不存在环境转让");

        LeaseView env = apply("apply-8", "alice", "teamA");
        assertReject(() -> harness.service.transfer(env.id(), transfer("tr-noop1", "alice", "alice", "teamA")),
                ErrorCode.VALIDATION_ERROR, "owner/team 都不变的空转让");
        assertReject(() -> harness.service.transfer(env.id(), transfer("tr-noop2", "alice", "  ", null)),
                ErrorCode.VALIDATION_ERROR, "目标全空的空转让");
        assertEquals(1L, harness.ledger.ownerView("alice").used());
        assertEquals(1L, harness.ledger.teamView("teamA").used());
    }
}
