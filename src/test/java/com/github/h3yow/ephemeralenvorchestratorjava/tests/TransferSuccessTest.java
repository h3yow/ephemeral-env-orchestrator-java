package com.github.h3yow.ephemeralenvorchestratorjava.tests;

import com.github.h3yow.ephemeralenvorchestratorjava.domain.Lease;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.LeaseStatus;
import com.github.h3yow.ephemeralenvorchestratorjava.error.BusinessException;
import com.github.h3yow.ephemeralenvorchestratorjava.error.ErrorCode;
import com.github.h3yow.ephemeralenvorchestratorjava.tests.support.TestHarness;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.ApplyLeaseRequest;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.LeaseView;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.RenewRequest;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.TransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 转让成功路径：环境原地易主（不重新供给/不换实例），归属与配额一次性迁移，
 * 新归属者可正常续租/终止，原归属者被挡在门外；相同维度不重复计数，来回转让不凭空产生配额。
 */
class TransferSuccessTest {

    private static final Logger log = LoggerFactory.getLogger(TransferSuccessTest.class);

    private TestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new TestHarness(3L, 100L);
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

    private RenewRequest renew(String operator) {
        RenewRequest r = new RenewRequest();
        r.setOperator(operator);
        r.setExtension("PT5M");
        return r;
    }

    @Test
    @DisplayName("owner+team 同时转让：环境原地不变（同一 id/endpoint/到期时间，不重新供给），配额整体迁移")
    void transferBothDimensions() {
        LeaseView env = apply("apply-1", "alice", "teamA");

        LeaseView moved = harness.service.transfer(env.id(),
                transfer("tr-1", "alice", "bob", "teamB"));

        assertEquals(env.id(), moved.id(), "判定依据：接手方拿到的还是原来那个环境（id 不变）");
        assertEquals("bob", moved.owner());
        assertEquals("teamB", moved.team());
        assertEquals(LeaseStatus.READY, moved.status());
        assertEquals(env.endpoint(), moved.endpoint(), "判定依据：endpoint 不变，不换实例");
        assertEquals(env.expiresAt(), moved.expiresAt(), "判定依据：到期时间不因转让变化");
        assertEquals(env.createdAt(), moved.createdAt());
        assertTrue(moved.quotaHeld(), "判定依据：转让期间配额始终被占用（源释放与目标占用同临界区完成）");

        assertEquals(1, harness.provisioner.getSuccessCount(), "判定依据：转让不触发重新供给");
        assertEquals(1, harness.provisioner.getOccupiedCount(), "判定依据：资源槽仍是原来那一个");
        assertEquals(0L, harness.ledger.ownerView("alice").used(), "判定依据：源 owner 已释放");
        assertEquals(1L, harness.ledger.ownerView("bob").used(), "判定依据：目标 owner 已占用");
        assertEquals(0L, harness.ledger.teamView("teamA").used(), "判定依据：源 team 已释放");
        assertEquals(1L, harness.ledger.teamView("teamB").used(), "判定依据：目标 team 已占用");
        log.info("转让成功：{} {}|{} -> {}|{} endpoint={} 供给次数仍={}",
                moved.id(), "alice", "teamA", moved.owner(), moved.team(), moved.endpoint(),
                harness.provisioner.getSuccessCount());
    }

    @Test
    @DisplayName("转让后新归属者可续租、可终止；原归属者的续租/终止全部以 NOT_OWNER 拒绝")
    void newOwnerOperatesOldOwnerBlocked() {
        LeaseView env = apply("apply-2", "alice", "teamA");
        harness.service.transfer(env.id(), transfer("tr-2", "alice", "bob", "teamB"));

        LeaseView renewed = harness.service.renew(env.id(), renew("bob"));
        assertEquals(java.time.Instant.parse("2026-09-22T00:15:00Z"), renewed.expiresAt());
        assertEquals("bob", renewed.owner());
        log.info("接手方 bob 续租成功 expiresAt={}", renewed.expiresAt());

        BusinessException renewByOld = assertThrows(BusinessException.class,
                () -> harness.service.renew(env.id(), renew("alice")));
        assertEquals(ErrorCode.NOT_OWNER, renewByOld.getCode());
        BusinessException terminateByOld = assertThrows(BusinessException.class,
                () -> harness.service.terminate(env.id(), "alice"));
        assertEquals(ErrorCode.NOT_OWNER, terminateByOld.getCode());
        log.info("原归属者 alice 再操作被挡：{} / {}", renewByOld.getCode(), terminateByOld.getCode());

        LeaseView terminated = harness.service.terminate(env.id(), "bob");
        assertEquals(LeaseStatus.TERMINATED, terminated.status());
        assertEquals(0L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.teamView("teamB").used());
    }

    @Test
    @DisplayName("只换 owner：team 维度水位纹丝不动，不能被先减后加重复计数")
    void transferOwnerOnlyKeepsTeam() {
        LeaseView env = apply("apply-3", "alice", "teamA");
        harness.service.transfer(env.id(), transfer("tr-3", "alice", "bob", null));

        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(1L, harness.ledger.ownerView("bob").used());
        assertEquals(1L, harness.ledger.teamView("teamA").used(),
                "判定依据：team 未变，该维度不释放也不占用");
        assertEquals(0L, harness.ledger.teamView("teamB").used());
    }

    @Test
    @DisplayName("只换 team：owner 维度水位不变")
    void transferTeamOnlyKeepsOwner() {
        LeaseView env = apply("apply-4", "alice", "teamA");
        harness.service.transfer(env.id(), transfer("tr-4", "alice", null, "teamB"));

        assertEquals(1L, harness.ledger.ownerView("alice").used(),
                "判定依据：owner 未变，该维度不动");
        assertEquals(0L, harness.ledger.teamView("teamA").used());
        assertEquals(1L, harness.ledger.teamView("teamB").used());
    }

    @Test
    @DisplayName("来回转让不凭空产生配额：多轮往返后水位与环境实际归属一致")
    void roundTripsConserveQuota() {
        LeaseView env = apply("apply-5", "alice", "teamA");
        for (int i = 0; i < 5; i++) {
            harness.service.transfer(env.id(), transfer("tr-ab-" + i, "alice", "bob", "teamB"));
            harness.service.transfer(env.id(), transfer("tr-ba-" + i, "bob", "alice", "teamA"));
        }
        assertEquals(1L, harness.ledger.ownerView("alice").used());
        assertEquals(0L, harness.ledger.ownerView("bob").used());
        assertEquals(1L, harness.ledger.teamView("teamA").used());
        assertEquals(0L, harness.ledger.teamView("teamB").used());
        assertEquals(1, harness.provisioner.getSuccessCount());
        log.info("5 轮往返转让后水位守恒：alice=1 bob=0 teamA=1 teamB=0");
    }

    @Test
    @DisplayName("综合水位对账：混合申请与转让后，每个 owner/team 的账本占用等于其名下仍占用配额的环境数")
    void ledgerMatchesLiveOwnership() {
        LeaseView e1 = apply("apply-6", "alice", "teamA");
        LeaseView e2 = apply("apply-7", "alice", "teamA");
        LeaseView e3 = apply("apply-8", "carol", "teamC");
        harness.service.transfer(e1.id(), transfer("tr-6", "alice", "bob", "teamB"));
        harness.service.transfer(e2.id(), transfer("tr-7", "alice", "bob", null));

        long aliceCount = countHeld("alice", null);
        long bobCount = countHeld("bob", null);
        long carolCount = countHeld("carol", null);
        assertEquals(aliceCount, harness.ledger.ownerView("alice").used());
        assertEquals(bobCount, harness.ledger.ownerView("bob").used());
        assertEquals(carolCount, harness.ledger.ownerView("carol").used());
        assertEquals(countHeld(null, "teamA"), harness.ledger.teamView("teamA").used());
        assertEquals(countHeld(null, "teamB"), harness.ledger.teamView("teamB").used());
        assertEquals(countHeld(null, "teamC"), harness.ledger.teamView("teamC").used());
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(2L, harness.ledger.ownerView("bob").used());
        assertEquals(1L, harness.ledger.teamView("teamA").used(), "e2 只换了 owner，teamA 仍计 1");
        assertEquals(1L, harness.ledger.teamView("teamB").used());
        log.info("对账完成：{}", e3.id());
    }

    private long countHeld(String owner, String team) {
        return harness.repository.findAll().stream()
                .filter(Lease::isQuotaHeld)
                .filter(l -> owner == null || owner.equals(l.getOwner()))
                .filter(l -> team == null || team.equals(l.getTeam()))
                .count();
    }
}
