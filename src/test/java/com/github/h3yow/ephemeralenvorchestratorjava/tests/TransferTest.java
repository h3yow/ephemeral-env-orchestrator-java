package com.github.h3yow.ephemeralenvorchestratorjava.tests;

import com.github.h3yow.ephemeralenvorchestratorjava.domain.Lease;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.LeaseStatus;
import com.github.h3yow.ephemeralenvorchestratorjava.error.BusinessException;
import com.github.h3yow.ephemeralenvorchestratorjava.error.ErrorCode;
import com.github.h3yow.ephemeralenvorchestratorjava.tests.support.ScriptedProvisioner;
import com.github.h3yow.ephemeralenvorchestratorjava.tests.support.TestHarness;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.ApplyLeaseRequest;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.LeaseView;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.RenewRequest;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.TransferLeaseRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 租约转让/接手测试：
 * 成功后归属与配额迁移、各类拒绝原因可区分、重复提交与冲突提交、
 * 以及转让与续租/终止/到期回收并发时的确定结论与配额水位对账。
 */
class TransferTest {

    private static final Logger log = LoggerFactory.getLogger(TransferTest.class);

    private TestHarness harness;

    @BeforeEach
    void setUp() {
        // owner 配额=2、team 配额=10，便于构造“目标 owner 已满”场景
        harness = new TestHarness(2L, 10L);
    }

    private ApplyLeaseRequest apply(String requestId, String owner, String team) {
        ApplyLeaseRequest r = new ApplyLeaseRequest();
        r.setRequestId(requestId);
        r.setOwner(owner);
        r.setTeam(team);
        r.setTtl("PT10M");
        return r;
    }

    private TransferLeaseRequest tx(String transferId, String operator, String toOwner, String toTeam) {
        TransferLeaseRequest r = new TransferLeaseRequest();
        r.setTransferRequestId(transferId);
        r.setOperator(operator);
        r.setTargetOwner(toOwner);
        r.setTargetTeam(toTeam);
        return r;
    }

    private RenewRequest renew(String operator) {
        RenewRequest r = new RenewRequest();
        r.setOperator(operator);
        r.setExtension("PT5M");
        return r;
    }

    /** 以租约上的 quotaHeld 标志为“实际存活占用”，逐 owner/team 聚合，与账本水位对账。 */
    private void assertLedgerMatchesLiveLeases() {
        Map<String, Long> ownerLive = new HashMap<>();
        Map<String, Long> teamLive = new HashMap<>();
        for (Lease lease : harness.repository.findAll()) {
            if (lease.isQuotaHeld()) {
                ownerLive.merge(lease.getOwner(), 1L, Long::sum);
                teamLive.merge(lease.getTeam(), 1L, Long::sum);
            }
        }
        for (Map.Entry<String, Long> e : ownerLive.entrySet()) {
            assertEquals(e.getValue(), harness.ledger.ownerView(e.getKey()).used(),
                    "判定依据：owner=" + e.getKey() + " 账本水位必须等于实际存活占用数");
        }
        for (Map.Entry<String, Long> e : teamLive.entrySet()) {
            assertEquals(e.getValue(), harness.ledger.teamView(e.getKey()).used(),
                    "判定依据：team=" + e.getKey() + " 账本水位必须等于实际存活占用数");
        }
        // 反向：账本里所有非零键也必须在存活聚合中出现
        harness.ledger.allOwnerViews().forEach((o, v) -> {
            if (v.used() > 0) {
                assertEquals(v.used(), ownerLive.getOrDefault(o, 0L),
                        "判定依据：账本 owner=" + o + " 有占用但找不到对应存活租约");
            }
        });
        harness.ledger.allTeamViews().forEach((t, v) -> {
            if (v.used() > 0) {
                assertEquals(v.used(), teamLive.getOrDefault(t, 0L),
                        "判定依据：账本 team=" + t + " 有占用但找不到对应存活租约");
            }
        });
    }

    @Test
    @DisplayName("转让成功：环境原地易主、实例与到期时间不变，配额整体迁移，新主人可续租/终止，原主人被挡住")
    void transferSuccessMigratesOwnershipAndQuota() {
        LeaseView lease = harness.service.apply(apply("tx-apply-1", "alice", "teamA"));
        Instant expiresAt = lease.expiresAt();
        String endpoint = lease.endpoint();
        log.info("输入：leaseId={} 归属 alice/teamA，转让给 bob/teamB", lease.id());

        LeaseView moved = harness.service.transfer(lease.id(), tx("tx-1", "alice", "bob", "teamB"));

        assertEquals(LeaseStatus.READY, moved.status());
        assertEquals("bob", moved.owner());
        assertEquals("teamB", moved.team());
        assertEquals(lease.id(), moved.id(), "判定依据：还是同一条环境，不重新供给");
        assertEquals(endpoint, moved.endpoint(), "判定依据：endpoint 不变，接手方拿到原来的实例");
        assertEquals(expiresAt, moved.expiresAt(), "判定依据：到期时间不变，不借转让续命");
        assertEquals(0L, harness.ledger.ownerView("alice").used(), "判定依据：源 owner 水位释放");
        assertEquals(1L, harness.ledger.ownerView("bob").used(), "判定依据：目标 owner 水位占用");
        assertEquals(0L, harness.ledger.teamView("teamA").used());
        assertEquals(1L, harness.ledger.teamView("teamB").used());
        assertEquals(1, harness.provisioner.getSuccessCount(), "判定依据：转让不触发供给，资源只分配一次");
        assertLedgerMatchesLiveLeases();
        log.info("转让后视图 owner={}/{} expiresAt={}", moved.owner(), moved.team(), moved.expiresAt());

        // 原主人再操作被挡
        BusinessException exRenew = assertThrows(BusinessException.class,
                () -> harness.service.renew(lease.id(), renew("alice")));
        assertEquals(ErrorCode.NOT_OWNER, exRenew.getCode());
        BusinessException exTerm = assertThrows(BusinessException.class,
                () -> harness.service.terminate(lease.id(), "alice"));
        assertEquals(ErrorCode.NOT_OWNER, exTerm.getCode());
        log.info("原主人 alice 的续租/终止均被 NOT_OWNER 拒绝");

        // 新主人可续租：在原到期时间上延长
        LeaseView renewed = harness.service.renew(lease.id(), renew("bob"));
        assertEquals(expiresAt.plus(Duration.ofMinutes(5)), renewed.expiresAt());
        // 新主人可终止并释放自己的配额
        LeaseView terminated = harness.service.terminate(lease.id(), "bob");
        assertEquals(LeaseStatus.TERMINATED, terminated.status());
        assertEquals(0L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.teamView("teamB").used());
        assertLedgerMatchesLiveLeases();
    }

    @Test
    @DisplayName("部分维度迁移与自我转让：同团队换⼈只动 owner 维度；同⼈换团队只动 team 维度；自己转自己不计数")
    void partialDimensionAndSelfTransfer() {
        LeaseView sameTeam = harness.service.apply(apply("tx-p-1", "alice", "teamA"));
        harness.service.transfer(sameTeam.id(), tx("tx-p-a", "alice", "bob", "teamA"));
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(1L, harness.ledger.ownerView("bob").used());
        assertEquals(1L, harness.ledger.teamView("teamA").used(), "判定依据：团队未变，水位不动");
        log.info("同团队换人后 teamA 水位仍为 {}", harness.ledger.teamView("teamA").used());

        LeaseView sameOwner = harness.service.apply(apply("tx-p-2", "carol", "teamA"));
        harness.service.transfer(sameOwner.id(), tx("tx-p-b", "carol", "carol", "teamC"));
        assertEquals(1L, harness.ledger.ownerView("carol").used(), "判定依据：owner 未变，水位不动");
        // teamA 仍有 bob 那条存活环境（同团队换人），故 teamA=1；carol 离开不影响 bob 的占用
        assertEquals(1L, harness.ledger.teamView("teamA").used());
        assertEquals(1L, harness.ledger.teamView("teamC").used());

        // 自己转自己：整体是无操作成功，水位不变
        LeaseView selfView = harness.service.transfer(sameOwner.id(),
                tx("tx-p-c", "carol", "carol", "teamC"));
        assertEquals("carol", selfView.owner());
        assertEquals(1L, harness.ledger.ownerView("carol").used());
        assertEquals(1L, harness.ledger.teamView("teamC").used(),
                "判定依据：源=目标时不能重复计数，也不能凭空变多");
        // 同键再交一次仍是幂等回放
        LeaseView replay = harness.service.transfer(sameOwner.id(),
                tx("tx-p-c", "carol", "carol", "teamC"));
        assertEquals(selfView.id(), replay.id());
        assertEquals(1L, harness.ledger.ownerView("carol").used());
        assertLedgerMatchesLiveLeases();
    }

    @Test
    @DisplayName("拒绝原因可区分：目标 owner 满 / 目标 team 满 / 非当前归属者 / 状态不允许 / 已终止 / 已回收")
    void rejectionReasonsDistinguishable() {
        // 目标 owner 已满：bob 已有 2 个（达上限 2）
        harness.service.apply(apply("tx-r-b1", "bob", "teamB"));
        harness.service.apply(apply("tx-r-b2", "bob", "teamB"));
        LeaseView a = harness.service.apply(apply("tx-r-a1", "alice", "teamA"));

        BusinessException ownerFull = assertThrows(BusinessException.class,
                () -> harness.service.transfer(a.id(), tx("tx-r-1", "alice", "bob", "teamX")));
        assertEquals(ErrorCode.OWNER_QUOTA_EXCEEDED, ownerFull.getCode(),
                "判定依据：bob used=2>=limit=2，必须与状态/归属原因区分");
        assertEquals("alice", harness.service.get(a.id()).owner(), "判定依据：被拒转让不得易主");
        assertEquals(1L, harness.ledger.ownerView("alice").used());
        assertEquals(2L, harness.ledger.ownerView("bob").used(), "判定依据：目标水位不得被吞扣");
        log.info("目标 owner 满被拒绝 code={} message={}", ownerFull.getCode(), ownerFull.getMessage());

        // 目标 team 满：换一个 team 配额=3 的夹具，teamFull 已占 3 个
        TestHarness teamHarness = new TestHarness(10L, 3L);
        teamHarness.service.apply(apply("g-1", "u1", "teamFull"));
        teamHarness.service.apply(apply("g-2", "u2", "teamFull"));
        teamHarness.service.apply(apply("g-3", "u3", "teamFull"));
        LeaseView outsider = teamHarness.service.apply(apply("g-4", "u4", "otherTeam"));
        BusinessException teamFull = assertThrows(BusinessException.class,
                () -> teamHarness.service.transfer(outsider.id(),
                        tx("tx-r-t1", "u4", "u4", "teamFull")));
        assertEquals(ErrorCode.TEAM_QUOTA_EXCEEDED, teamFull.getCode(),
                "判定依据：teamFull used=3>=limit=3；owner 未变故不是 owner 原因");
        assertEquals(0L, teamHarness.ledger.ownerView("u9").used());
        log.info("目标 team 满被拒绝 code={} message={}", teamFull.getCode(), teamFull.getMessage());

        // 发起人不是当前归属者
        BusinessException notOwner = assertThrows(BusinessException.class,
                () -> harness.service.transfer(a.id(), tx("tx-r-2", "eve", "bob", "teamX")));
        assertEquals(ErrorCode.NOT_OWNER, notOwner.getCode());
        log.info("非归属者发起被拒绝 code={}", notOwner.getCode());

        // 环境当前状态不允许转让：可重试 FAILED（未就绪，也未结束）。用全新夹具并先设脚本，
        // 避免全局调用序号导致脚本不生效。
        TestHarness failHarness = new TestHarness(2L, 10L);
        failHarness.provisioner.setScript(ScriptedProvisioner.Mode.EXHAUSTED, 2);
        LeaseView failed = failHarness.service.apply(apply("tx-r-f1", "dave", "teamD"));
        assertEquals(LeaseStatus.FAILED, failed.status());
        BusinessException notReady = assertThrows(BusinessException.class,
                () -> failHarness.service.transfer(failed.id(), tx("tx-r-3", "dave", "bob", "teamX")));
        assertEquals(ErrorCode.TRANSFER_NOT_ALLOWED, notReady.getCode(),
                "判定依据：FAILED 非 READY，状态原因须独立于终态原因");
        assertEquals(1L, failHarness.ledger.ownerView("dave").used(),
                "判定依据：被拒转让不得改动水位");
        log.info("非 READY 转让被拒绝 code={} message={}", notReady.getCode(), notReady.getMessage());

        // 已终止
        LeaseView b = harness.service.apply(apply("tx-r-a2", "alice", "teamA"));
        harness.service.terminate(b.id(), "alice");
        BusinessException terminated = assertThrows(BusinessException.class,
                () -> harness.service.transfer(b.id(), tx("tx-r-4", "alice", "bob", "teamX")));
        assertEquals(ErrorCode.LEASE_ALREADY_TERMINATED, terminated.getCode());

        // 已过期回收：此时钟点上本夹具内 4 条同时刻到期（bob*2 + alice 的 a,c）被一并回收
        LeaseView c = harness.service.apply(apply("tx-r-a3", "alice", "teamA"));
        harness.clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        int reaped = harness.service.reapExpired();
        assertEquals(4, reaped, "判定依据：全部同时到期租约一次性回收");
        BusinessException expired = assertThrows(BusinessException.class,
                () -> harness.service.transfer(c.id(), tx("tx-r-5", "alice", "bob", "teamX")));
        assertEquals(ErrorCode.LEASE_ALREADY_EXPIRED, expired.getCode(),
                "判定依据：已结束/已回收必须是独立原因，不能糊成笼统失败");
        assertLedgerMatchesLiveLeases();
    }

    @Test
    @DisplayName("幂等：同 transferRequestId 串行与并发重复提交只生效一次，结论一致，目标配额只占一次")
    void duplicateTransferIdempotent() throws Exception {
        LeaseView lease = harness.service.apply(apply("tx-d-1", "alice", "teamA"));

        LeaseView v1 = harness.service.transfer(lease.id(), tx("tx-dup", "alice", "bob", "teamB"));
        LeaseView v2 = harness.service.transfer(lease.id(), tx("tx-dup", "alice", "bob", "teamB"));
        assertEquals(v1.id(), v2.id());
        assertEquals("bob", v2.owner());
        assertEquals(1L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.ownerView("alice").used());

        // 高并发重复提交（两端几乎同时点）
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> owners = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ErrorCode> errors = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    owners.add(harness.service.transfer(lease.id(),
                            tx("tx-dup", "alice", "bob", "teamB")).owner());
                } catch (BusinessException ex) {
                    errors.add(ex.getCode());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        assertEquals(threads, owners.size(), "判定依据：所有重复提交拿到一致结论，不是错误");
        assertTrue(errors.isEmpty());
        assertEquals(Set.of("bob"), new HashSet<>(owners));
        assertEquals(1L, harness.ledger.ownerView("bob").used(),
                "判定依据：无论重复多少次，目标配额只占用一次");
        assertEquals(1, harness.provisioner.getSuccessCount(), "判定依据：不重复供给");
        log.info("并发 {} 次重复转让 -> owner={} bob 水位={}", threads, owners.size(),
                harness.ledger.ownerView("bob").used());
        assertLedgerMatchesLiveLeases();
    }

    @Test
    @DisplayName("冲突：同一 transferRequestId 转另一条环境或另一个目标，明确冲突拒绝且说明此前用在哪")
    void transferRequestConflict() {
        LeaseView l1 = harness.service.apply(apply("tx-c-1", "alice", "teamA"));
        LeaseView l2 = harness.service.apply(apply("tx-c-2", "alice", "teamA"));

        harness.service.transfer(l1.id(), tx("tx-key", "alice", "bob", "teamB"));

        // 同一标识转另一条环境
        BusinessException otherLease = assertThrows(BusinessException.class,
                () -> harness.service.transfer(l2.id(), tx("tx-key", "alice", "carol", "teamC")));
        assertEquals(ErrorCode.TRANSFER_REQUEST_CONFLICT, otherLease.getCode());
        assertTrue(otherLease.getMessage().contains(l1.id()),
                "判定依据：冲突结论必须说清标识此前用在哪条环境，实际=" + otherLease.getMessage());
        assertTrue(otherLease.getMessage().contains("bob"),
                "判定依据：冲突信息应包含此前的目标归属");
        log.info("转另一条环境冲突 code={} message={}", otherLease.getCode(), otherLease.getMessage());

        // 同一标识对同一条环境转另一个目标
        BusinessException otherTarget = assertThrows(BusinessException.class,
                () -> harness.service.transfer(l1.id(), tx("tx-key", "bob", "carol", "teamC")));
        assertEquals(ErrorCode.TRANSFER_REQUEST_CONFLICT, otherTarget.getCode());
        assertEquals("bob", harness.service.get(l1.id()).owner(), "判定依据：冲突不得悄悄改归属");
        assertEquals(0L, harness.ledger.ownerView("carol").used(),
                "判定依据：冲突提交不得占用新目标配额");

        // 申请/转让幂等键混用也冲突
        BusinessException mixed = assertThrows(BusinessException.class,
                () -> harness.service.apply(apply("tx-key", "zoe", "teamZ")));
        assertEquals(ErrorCode.TRANSFER_REQUEST_CONFLICT, mixed.getCode());
        assertEquals(0L, harness.ledger.ownerView("zoe").used());
        log.info("幂等键混用冲突 code={} message={}", mixed.getCode(), mixed.getMessage());
        assertLedgerMatchesLiveLeases();
    }

    @Test
    @DisplayName("并发：到期边界上转让/续租/回收同时发生，结论确定——全部以 LEASE_ALREADY_EXPIRED 拒绝，配额不丢")
    void transferVsExpiryAtBoundary() throws Exception {
        LeaseView lease = harness.service.apply(apply("tx-e-1", "alice", "teamA"));
        // now == expiresAt：isExpiredAt 为 true
        harness.clock.set(Instant.parse("2026-09-22T00:10:00Z"));

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<ErrorCode> rejects = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<LeaseStatus> oks = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Integer> reaps = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    switch (idx % 3) {
                        case 0 -> {
                            try {
                                harness.service.transfer(lease.id(),
                                        tx("tx-e-key-" + idx, "alice", "bob", "teamB"));
                                oks.add(LeaseStatus.READY);
                            } catch (BusinessException ex) {
                                rejects.add(ex.getCode());
                            }
                        }
                        case 1 -> {
                            try {
                                harness.service.renew(lease.id(), renew("alice"));
                                oks.add(LeaseStatus.READY);
                            } catch (BusinessException ex) {
                                rejects.add(ex.getCode());
                            }
                        }
                        default -> reaps.add(harness.service.reapExpired());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        LeaseView fin = harness.service.get(lease.id());
        long flipCount = reaps.stream().mapToInt(Integer::intValue).sum();
        log.info("边界并发结果：终态={} 成功={} 拒绝codes={} 回收翻转={}",
                fin.status(), oks.size(), rejects.stream().distinct().toList(), flipCount);

        assertEquals(LeaseStatus.EXPIRED, fin.status(), "判定依据：已结束环境不能被转走也不能续租");
        assertTrue(oks.isEmpty(), "判定依据：边界上转让与续租都不允许成功");
        assertTrue(rejects.stream().allMatch(c -> c == ErrorCode.LEASE_ALREADY_EXPIRED),
                "判定依据：拒绝原因统一且明确，实际=" + rejects.stream().distinct().toList());
        assertTrue(flipCount <= 1, "判定依据：READY->EXPIRED 全局至多一次");
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(0L, harness.ledger.ownerView("bob").used(),
                "判定依据：转让没成功就绝不能吞掉目标配额");
        assertEquals(0L, harness.ledger.teamView("teamB").used());
        assertEquals(0, harness.provisioner.getOccupiedCount(), "判定依据：资源槽随回收释放");
        assertLedgerMatchesLiveLeases();
    }

    @Test
    @DisplayName("并发：同一环境转让与终止对冲，只有一个确定结论，水位与存活环境严格对账")
    void transferVsTerminateStorm() throws Exception {
        LeaseView lease = harness.service.apply(apply("tx-s-1", "alice", "teamA"));

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<ErrorCode> rejects = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> transferOkOwners = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            final boolean doTransfer = i % 2 == 0;
            pool.submit(() -> {
                try {
                    start.await();
                    if (doTransfer) {
                        try {
                            transferOkOwners.add(harness.service.transfer(lease.id(),
                                    tx("tx-s-key", "alice", "bob", "teamA")).owner());
                        } catch (BusinessException ex) {
                            rejects.add(ex.getCode());
                        }
                    } else {
                        try {
                            harness.service.terminate(lease.id(), "alice");
                        } catch (BusinessException ex) {
                            rejects.add(ex.getCode());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        LeaseView fin = harness.service.get(lease.id());
        log.info("转让/终止对冲结果：终态={} 转让成功次数={} 拒绝codes={} alice={} bob={}",
                fin.status(), transferOkOwners.size(), rejects.stream().distinct().toList(),
                harness.ledger.ownerView("alice").used(), harness.ledger.ownerView("bob").used());

        if (fin.status() == LeaseStatus.READY) {
            // 转让先胜出：环境活着且属于 bob，alice 的终止全部 NOT_OWNER
            assertEquals("bob", fin.owner());
            assertFalse(transferOkOwners.isEmpty());
            assertTrue(transferOkOwners.stream().allMatch("bob"::equals));
            assertEquals(0L, harness.ledger.ownerView("alice").used());
            assertEquals(1L, harness.ledger.ownerView("bob").used());
            assertTrue(rejects.stream().allMatch(c -> c == ErrorCode.NOT_OWNER),
                    "判定依据：易主后原主人终止只能是 NOT_OWNER，实际=" + rejects.stream().distinct().toList());
        } else {
            // 终止先胜出：环境 TERMINATED，所有转让以明确终态原因拒绝，目标不占配额
            assertEquals(LeaseStatus.TERMINATED, fin.status());
            assertTrue(transferOkOwners.isEmpty());
            assertEquals(0L, harness.ledger.ownerView("alice").used());
            assertEquals(0L, harness.ledger.ownerView("bob").used(),
                    "判定依据：转让未生效，配额不能被吞");
            assertTrue(rejects.stream().allMatch(c -> c == ErrorCode.LEASE_ALREADY_TERMINATED),
                    "判定依据：终止后转让原因必须明确，实际=" + rejects.stream().distinct().toList());
        }
        assertLedgerMatchesLiveLeases();
    }

    @Test
    @DisplayName("并发：到期前转让与续租交错，新主人续租成功、原主人被挡，水位对账一致")
    void transferAndRenewBeforeExpiry() throws Exception {
        LeaseView lease = harness.service.apply(apply("tx-m-1", "alice", "teamA"));
        harness.clock.set(Instant.parse("2026-09-22T00:09:59Z")); // 到期前 1 秒

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<ErrorCode> rejects = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (idx % 2 == 0) {
                        try {
                            harness.service.transfer(lease.id(), tx("tx-m-key", "alice", "bob", "teamA"));
                        } catch (BusinessException ex) {
                            rejects.add(ex.getCode());
                        }
                    } else {
                        // 一半用原主人、一半用新主人续租，谁是当前 owner 谁才能成功
                        String op = idx % 4 == 1 ? "alice" : "bob";
                        try {
                            harness.service.renew(lease.id(), renew(op));
                        } catch (BusinessException ex) {
                            rejects.add(ex.getCode());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        LeaseView fin = harness.service.get(lease.id());
        log.info("转让/续租交错结果：owner={} status={} expiresAt={} 拒绝codes={}",
                fin.owner(), fin.status(), fin.expiresAt(), rejects.stream().distinct().toList());

        assertEquals(LeaseStatus.READY, fin.status(), "判定依据：未到期环境必须活着");
        assertEquals("bob", fin.owner(), "判定依据：转让幂等生效，最终归属确定为 bob");
        assertTrue(fin.expiresAt().isAfter(Instant.parse("2026-09-22T00:10:00Z")),
                "判定依据：至少有一次新主人续租成功，到期时间被顺延，实际=" + fin.expiresAt());
        // 原主人续租必然失败；不会出现任何“状态/终态”类拒绝
        assertTrue(rejects.stream().allMatch(c -> c == ErrorCode.NOT_OWNER),
                "判定依据：未到期下唯一可能的拒绝是易主后的 NOT_OWNER，实际="
                        + rejects.stream().distinct().toList());
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(1L, harness.ledger.ownerView("bob").used());
        assertEquals(1L, harness.ledger.teamView("teamA").used(), "判定依据：团队未变");
        assertLedgerMatchesLiveLeases();
    }
}
