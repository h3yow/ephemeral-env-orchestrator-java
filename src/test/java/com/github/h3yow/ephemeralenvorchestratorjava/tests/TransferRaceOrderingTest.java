package com.github.h3yow.ephemeralenvorchestratorjava.tests;

import com.github.h3yow.ephemeralenvorchestratorjava.domain.LeaseStatus;
import com.github.h3yow.ephemeralenvorchestratorjava.error.BusinessException;
import com.github.h3yow.ephemeralenvorchestratorjava.error.ErrorCode;
import com.github.h3yow.ephemeralenvorchestratorjava.tests.support.RaceDirector;
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

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 转让并发裁决的<b>确定性</b>用例：用 {@link RaceDirector} 精确指定操作线性化顺序，
 * 把“转让先 / 对方先”两种结论分别钉死，断言到归属、各级配额、资源槽、
 * 以及旧/新归属者后续操作权限的每一个细节。
 *
 * <p>随机顺序下的不变量由 {@link TransferConcurrencyTest} 的万箭齐发压测覆盖；
 * 本类保证两种顺序本身都可复现、可断言。</p>
 */
class TransferRaceOrderingTest {

    private static final Logger log = LoggerFactory.getLogger(TransferRaceOrderingTest.class);

    private TestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new TestHarness(20L, 200L);
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

    private static RenewRequest renew(String operator, String extension) {
        RenewRequest r = new RenewRequest();
        r.setOperator(operator);
        r.setExtension(extension);
        return r;
    }

    @Test
    @DisplayName("确定性顺序[转让→终止]：转让先线性化并原地易主，随后 alice 终止被 NOT_OWNER，环境仍 READY 在 bob 名下")
    void transferThenTerminateOrdering() throws Exception {
        LeaseView env = apply("ord-tt-1", "alice", "teamA");

        ConcurrentLinkedQueue<LeaseView> transferOk = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ErrorCode> rejects = new ConcurrentLinkedQueue<>();
        // 第 1 步在独立线程完成转让并 join，第 2 步才在另一独立线程发起终止：线性化顺序唯一确定
        RaceDirector.runInOrder(List.of(
                () -> transferOk.add(harness.service.transfer(env.id(),
                        transfer("ord-tt-tr", "alice", "bob", "teamB"))),
                () -> {
                    try {
                        harness.service.terminate(env.id(), "alice");
                    } catch (BusinessException ex) {
                        rejects.add(ex.getCode());
                    }
                }
        ));

        assertEquals(1, transferOk.size());
        LeaseView moved = transferOk.peek();
        assertEquals(env.id(), moved.id(), "判定依据：原地易主，id 不变");
        assertEquals(env.endpoint(), moved.endpoint(), "判定依据：endpoint/实例不变，不重新供给");
        assertEquals(env.expiresAt(), moved.expiresAt(), "判定依据：到期时间不变");
        assertEquals(List.of(ErrorCode.NOT_OWNER), List.copyOf(rejects),
                "判定依据：易主后原归属者终止被 NOT_OWNER 挡下");

        LeaseView finalState = harness.service.get(env.id());
        assertEquals(LeaseStatus.READY, finalState.status(),
                "判定依据：被 NOT_OWNER 拒绝的终止不能让环境结束");
        assertEquals("bob", finalState.owner());
        assertEquals("teamB", finalState.team());
        // 各级配额已随归属迁移
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(1L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.teamView("teamA").used());
        assertEquals(1L, harness.ledger.teamView("teamB").used());
        assertEquals(1, harness.provisioner.getOccupiedCount());
        assertEquals(1, harness.provisioner.getSuccessCount(), "判定依据：转让不重新供给");

        // 旧归属者续租/再转让/终止全部被挡
        assertEquals(ErrorCode.NOT_OWNER, assertThrows(BusinessException.class,
                () -> harness.service.renew(env.id(), renew("alice", "PT1M"))).getCode());
        assertEquals(ErrorCode.NOT_OWNER, assertThrows(BusinessException.class,
                () -> harness.service.transfer(env.id(), transfer("ord-tt-late", "alice", "carol", "teamC")))
                .getCode());
        // 新归属者能续租：在原到期时间上叠加
        LeaseView renewed = harness.service.renew(env.id(), renew("bob", "PT2M"));
        assertEquals(Instant.parse("2026-09-22T00:12:00Z"), renewed.expiresAt());
        assertEquals("bob", renewed.owner());
        // 新归属者能终止：环境结束、两级配额与资源槽全部释放
        LeaseView terminated = harness.service.terminate(env.id(), "bob");
        assertEquals(LeaseStatus.TERMINATED, terminated.status());
        assertEquals(0L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.teamView("teamB").used());
        assertEquals(0, harness.provisioner.getOccupiedCount());
        log.info("转让→终止顺序：环境保留在 bob 名下，alice 终止被 NOT_OWNER；bob 续租并终止成功");
    }

    @Test
    @DisplayName("确定性顺序[终止→转让]：终止先线性化后，转让拿到 LEASE_ALREADY_TERMINATED，已结束环境绝不被转走，配额不被吞")
    void terminateThenTransferOrdering() throws Exception {
        LeaseView env = apply("ord-tt-2", "alice", "teamA");

        java.util.concurrent.atomic.AtomicReference<LeaseView> terminatedView =
                new java.util.concurrent.atomic.AtomicReference<>();
        ConcurrentLinkedQueue<ErrorCode> rejects = new ConcurrentLinkedQueue<>();
        RaceDirector.runInOrder(List.of(
                () -> terminatedView.set(harness.service.terminate(env.id(), "alice")),
                () -> {
                    try {
                        harness.service.transfer(env.id(), transfer("ord-tt-tr2", "alice", "bob", "teamB"));
                    } catch (BusinessException ex) {
                        rejects.add(ex.getCode());
                    }
                }
        ));

        assertEquals(LeaseStatus.TERMINATED, terminatedView.get().status());        assertEquals(List.of(ErrorCode.LEASE_ALREADY_TERMINATED), List.copyOf(rejects),
                "判定依据：已结束环境的转让只能得到明确的终态拒绝码");
        LeaseView finalState = harness.service.get(env.id());
        assertEquals(LeaseStatus.TERMINATED, finalState.status());
        assertEquals("alice", finalState.owner(), "判定依据：转让失败，归属纹丝不动（仍挂在原 owner 名下）");
        assertEquals("teamA", finalState.team());
        // 源侧：终止已释放；目标侧：一次失败转让绝不能占用或吞掉任何配额
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(0L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.teamView("teamA").used());
        assertEquals(0L, harness.ledger.teamView("teamB").used());
        assertEquals(0, harness.provisioner.getOccupiedCount());
        assertEquals(1, harness.provisioner.getSuccessCount(), "判定依据：无重新供给");

        // 再重复转让仍是同一明确结论；bob 从未成为 owner，其任何操作都是 NOT_OWNER
        BusinessException again = assertThrows(BusinessException.class,
                () -> harness.service.transfer(env.id(), transfer("ord-tt-tr2-again", "alice", "bob", "teamB")));
        assertEquals(ErrorCode.LEASE_ALREADY_TERMINATED, again.getCode());
        assertEquals(ErrorCode.NOT_OWNER, assertThrows(BusinessException.class,
                () -> harness.service.renew(env.id(), renew("bob", "PT1M"))).getCode());
        assertEquals(ErrorCode.LEASE_ALREADY_TERMINATED, assertThrows(BusinessException.class,
                () -> harness.service.renew(env.id(), renew("alice", "PT1M"))).getCode());
        // 所有后续被拒操作之后水位仍为零
        assertEquals(0L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.teamView("teamB").used());
        log.info("终止→转让顺序：TERMINATED 不可转让，源/目标两级配额均未被占用或吞掉");
    }

    @Test
    @DisplayName("确定性顺序[续租→转让]：alice 先续租（00:10→00:12），随后转让把延长后的到期时间原地带给 bob")
    void renewThenTransferOrdering() throws Exception {
        LeaseView env = apply("ord-rt-1", "alice", "teamA");

        java.util.concurrent.atomic.AtomicReference<LeaseView> renewed =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<LeaseView> moved =
                new java.util.concurrent.atomic.AtomicReference<>();
        RaceDirector.runInOrder(List.of(
                () -> renewed.set(harness.service.renew(env.id(), renew("alice", "PT2M"))),
                () -> moved.set(harness.service.transfer(env.id(),
                        transfer("ord-rt-tr", "alice", "bob", "teamB")))
        ));

        assertEquals(Instant.parse("2026-09-22T00:12:00Z"), renewed.get().expiresAt());
        assertEquals("bob", moved.get().owner());
        assertEquals("teamB", moved.get().team());
        assertEquals(Instant.parse("2026-09-22T00:12:00Z"), moved.get().expiresAt(),
                "判定依据：续租延长后的到期时间随环境原样交接，转让不重算到期时间");
        assertEquals(env.endpoint(), moved.get().endpoint());
        assertEquals(LeaseStatus.READY, harness.service.get(env.id()).status());
        // 配额随归属迁移，且没有因为“续租 + 转让”被扣两次
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(1L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.teamView("teamA").used());
        assertEquals(1L, harness.ledger.teamView("teamB").used());
        assertEquals(1, harness.provisioner.getOccupiedCount());
        log.info("续租→转让顺序：延长后的 expiresAt=00:12 由 bob 接手，配额只搬一次");
    }

    @Test
    @DisplayName("确定性顺序[转让→续租]：转让后 alice 续租 NOT_OWNER，bob 续租在接收到期时间上继续叠加")
    void transferThenRenewOrdering() throws Exception {
        LeaseView env = apply("ord-rt-2", "alice", "teamA");

        java.util.concurrent.atomic.AtomicReference<LeaseView> moved =
                new java.util.concurrent.atomic.AtomicReference<>();
        ConcurrentLinkedQueue<ErrorCode> rejects = new ConcurrentLinkedQueue<>();
        java.util.concurrent.atomic.AtomicReference<LeaseView> bobRenewed =
                new java.util.concurrent.atomic.AtomicReference<>();
        RaceDirector.runInOrder(List.of(
                () -> moved.set(harness.service.transfer(env.id(),
                        transfer("ord-rt-tr2", "alice", "bob", "teamB"))),
                () -> {
                    try {
                        harness.service.renew(env.id(), renew("alice", "PT2M"));
                    } catch (BusinessException ex) {
                        rejects.add(ex.getCode());
                    }
                },
                () -> bobRenewed.set(harness.service.renew(env.id(), renew("bob", "PT1M")))
        ));

        assertEquals("bob", moved.get().owner());
        assertEquals(List.of(ErrorCode.NOT_OWNER), List.copyOf(rejects),
                "判定依据：原归属者易主后续租被 NOT_OWNER 挡下，到期时间不被其改动");
        // alice 的续租被拒未生效：bob 续租前到期时间仍是 00:10，叠加 1 分钟 -> 00:11
        assertEquals(Instant.parse("2026-09-22T00:11:00Z"), bobRenewed.get().expiresAt(),
                "判定依据：被拒的旧 owner 续租绝不能延长环境；新 owner 在 00:10 基础上续到 00:11");
        assertEquals("bob", bobRenewed.get().owner());
        assertEquals(1L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(1L, harness.ledger.teamView("teamB").used());
        assertEquals(0L, harness.ledger.teamView("teamA").used());
        log.info("转让→续租顺序：alice 续租 NOT_OWNER，bob 在接收到期时间上续到 00:11");
    }

    @Test
    @DisplayName("确定性顺序[回收→转让]：到期点先回收则 EXPIRED 原子落地，随后转让以 LEASE_ALREADY_EXPIRED 拒绝，两边配额归零")
    void reapThenTransferAtBoundaryOrdering() throws Exception {
        LeaseView env = apply("ord-er-1", "alice", "teamA");
        harness.clock.set(Instant.parse("2026-09-22T00:10:00Z")); // now == expiresAt

        java.util.concurrent.atomic.AtomicInteger firstReap = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger secondReap = new java.util.concurrent.atomic.AtomicInteger();
        ConcurrentLinkedQueue<ErrorCode> rejects = new ConcurrentLinkedQueue<>();
        RaceDirector.runInOrder(List.of(
                () -> firstReap.set(harness.service.reapExpired()),
                () -> {
                    try {
                        harness.service.transfer(env.id(), transfer("ord-er-tr", "alice", "bob", "teamB"));
                    } catch (BusinessException ex) {
                        rejects.add(ex.getCode());
                    }
                },
                () -> secondReap.set(harness.service.reapExpired())
        ));

        assertEquals(1, firstReap.get(), "判定依据：第一步确定完成 READY->EXPIRED 翻转");
        assertEquals(List.of(ErrorCode.LEASE_ALREADY_EXPIRED), List.copyOf(rejects),
                "判定依据：已结束环境绝不能被转走");
        assertEquals(0, secondReap.get(), "判定依据：终态翻转全局至多一次，重复回收为空操作");

        LeaseView finalState = harness.service.get(env.id());
        assertEquals(LeaseStatus.EXPIRED, finalState.status());
        assertEquals("alice", finalState.owner(), "判定依据：失败转让不改归属");
        assertEquals("teamA", finalState.team());
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(0L, harness.ledger.ownerView("bob").used(),
                "判定依据：目标 owner 配额绝不被一次失败转让吞掉");
        assertEquals(0L, harness.ledger.teamView("teamA").used());
        assertEquals(0L, harness.ledger.teamView("teamB").used());
        assertEquals(0, harness.provisioner.getOccupiedCount(), "判定依据：资源槽随回收释放");
        // 新归属者不能接手一条已结束的环境；旧归属者也只能拿到终态拒绝
        assertEquals(ErrorCode.NOT_OWNER, assertThrows(BusinessException.class,
                () -> harness.service.renew(env.id(), renew("bob", "PT1M"))).getCode());
        assertEquals(ErrorCode.LEASE_ALREADY_EXPIRED, assertThrows(BusinessException.class,
                () -> harness.service.transfer(env.id(), transfer("ord-er-tr-late", "alice", "bob", "teamB")))
                .getCode());
        log.info("回收→转让顺序：EXPIRED 原子落地，转让 LEASE_ALREADY_EXPIRED，配额/槽位均未被吞");
    }

    @Test
    @DisplayName("确定性顺序[转让→回收]：到期前一秒先转让成功；到点回收只回收新归属者 bob 名下环境，源 alice 配额不被二次释放")
    void transferThenReapOrdering() throws Exception {
        LeaseView env = apply("ord-er-2", "alice", "teamA");
        harness.clock.set(Instant.parse("2026-09-22T00:09:59Z")); // 到期前 1 秒

        java.util.concurrent.atomic.AtomicReference<LeaseView> moved =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger earlyReap = new java.util.concurrent.atomic.AtomicInteger();
        RaceDirector.runInOrder(List.of(
                () -> moved.set(harness.service.transfer(env.id(),
                        transfer("ord-er-tr2", "alice", "bob", "teamB"))),
                () -> earlyReap.set(harness.service.reapExpired())
        ));

        assertEquals("bob", moved.get().owner());
        assertEquals(0, earlyReap.get(), "判定依据：00:09:59 < expiresAt=00:10，回收不得提前命中已转出的环境");
        // 已转出：alice 名下已无水位，bob/teamB 各持有 1
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(1L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.teamView("teamA").used());
        assertEquals(1L, harness.ledger.teamView("teamB").used());
        assertEquals(1, harness.provisioner.getOccupiedCount());

        // 时钟推到到期点：回收命中的是 bob/teamB 名下那条环境，释放的也是 bob/teamB 的配额
        harness.clock.set(Instant.parse("2026-09-22T00:10:00Z"));
        assertEquals(1, harness.service.reapExpired(), "判定依据：恰好回收一条（已转给 bob 的那条）");
        LeaseView finalState = harness.service.get(env.id());
        assertEquals(LeaseStatus.EXPIRED, finalState.status());
        assertEquals("bob", finalState.owner(),
                "判定依据：回收不改变归属字段，环境到期前归属是谁就挂在谁名下");
        assertEquals(0L, harness.ledger.ownerView("bob").used(),
                "判定依据：到期释放的是接手方 owner 配额");
        assertEquals(0L, harness.ledger.teamView("teamB").used(),
                "判定依据：到期释放的是接手方 team 配额");
        assertEquals(0L, harness.ledger.ownerView("alice").used(),
                "判定依据：源 owner 配额在转让时已释放，回收不得二次释放（计数无负数/无串账）");
        assertEquals(0L, harness.ledger.teamView("teamA").used());
        assertEquals(0, harness.provisioner.getOccupiedCount());
        log.info("转让→回收顺序：先转出，到点回收 bob/teamB 名下环境，alice 侧不串账、不重复释放");
    }
}
