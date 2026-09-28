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

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 转让与续租 / 终止 / 到期回收并发：全部入口经同一全局锁串行裁决，只有一个确定结论；
 * 压测结束后每个 owner / 每个 team 的占用水位必须与名下仍存活（quotaHeld）的环境数严格相等。
 */
class TransferConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(TransferConcurrencyTest.class);

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

    private void raceOneLease(int threads, String leaseId,
                              java.util.function.Consumer<Integer> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    action.accept(idx);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("转让 vs 终止同时发生：二选一确定胜出，绝不出现 TERMINATED 环境仍被转走或配额被吞")
    void transferVsTerminate() throws Exception {
        // 重复多轮：两个线性化分支（转让先 / 终止先）都可能被调度命中，
        // 无论命中哪个分支，下列强断言都必须成立——这正是“跑多少遍都稳定”的含义。
        for (int round = 0; round < 15; round++) {
            runTransferVsTerminateOnce(round);
        }
    }

    private void runTransferVsTerminateOnce(int round) throws Exception {
        LeaseView env = apply("race-t-" + round, "alice", "teamA");
        int provisionSuccessAtStart = harness.provisioner.getSuccessCount();
        int threads = 24;
        ConcurrentLinkedQueue<LeaseView> transferOk = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ErrorCode> rejects = new ConcurrentLinkedQueue<>();
        AtomicInteger terminates = new AtomicInteger();

        raceOneLease(threads, env.id(), idx -> {
            try {
                if (idx % 2 == 0) {
                    transferOk.add(harness.service.transfer(env.id(),
                            transfer("race-t-" + round + "-" + idx, "alice", "bob", "teamB")));
                } else {
                    LeaseView v = harness.service.terminate(env.id(), "alice");
                    if (v.status() == LeaseStatus.TERMINATED) {
                        terminates.incrementAndGet();
                    }
                }
            } catch (BusinessException ex) {
                rejects.add(ex.getCode());
            }
        });

        LeaseView finalState = harness.service.get(env.id());
        log.info("round={} 判定结果：终态={} 转让成功次数={} 终止成功次数={} 拒绝码={}",
                round, finalState.status(), transferOk.size(), terminates.get(),
                rejects.stream().distinct().toList());

        if (finalState.status() == LeaseStatus.TERMINATED) {
            assertTerminateWonRace(env, finalState, transferOk, rejects, terminates,
                    provisionSuccessAtStart);
        } else {
            assertTransferWonRace(env, finalState, transferOk, rejects, terminates,
                    provisionSuccessAtStart);
        }
    }

    /** 终止先线性化：环境留在 alice 名下并 TERMINATED，转让一次都不能生效，配额/资源槽全部归零。 */
    private void assertTerminateWonRace(LeaseView env, LeaseView finalState,
                                        ConcurrentLinkedQueue<LeaseView> transferOk,
                                        ConcurrentLinkedQueue<ErrorCode> rejects,
                                        AtomicInteger terminates, int provisionSuccessAtStart) {
        // 终止先胜出：所有转让必须被拒，且只能是 LEASE_ALREADY_TERMINATED
        assertTrue(transferOk.isEmpty(), "判定依据：已结束环境绝不能被转走");
        assertTrue(rejects.stream().allMatch(c -> c == ErrorCode.LEASE_ALREADY_TERMINATED),
                "实际=" + rejects.stream().distinct().toList());
        assertEquals(1, terminates.get(), "判定依据：READY->TERMINATED 全局只能翻转一次");
        // 被拒转让不得改动归属：终止不换 owner，环境仍挂在 alice/teamA 名下（已是终态）
        assertEquals("alice", finalState.owner(), "判定依据：转让失败时归属纹丝不动");
        assertEquals("teamA", finalState.team());
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(0L, harness.ledger.ownerView("bob").used(),
                "判定依据：转让未成功则目标 owner 配额绝不能被占用（配额不被吞）");
        assertEquals(0L, harness.ledger.teamView("teamA").used());
        assertEquals(0L, harness.ledger.teamView("teamB").used(),
                "判定依据：转让未成功则目标 team 配额绝不能被占用（配额不被吞）");
        assertEquals(0, harness.provisioner.getOccupiedCount(),
                "判定依据：终止已释放唯一资源槽，不泄漏");
        assertEquals(0, harness.provisioner.getSuccessCount() - provisionSuccessAtStart,
                "判定依据：失败路径绝不能触发重新供给");

        // 后续操作校验：旧归属者只能拿到终态拒绝；未接手成功的 bob 连归属校验都过不了；
        // 环境不可能被一次迟到的转让“复活”或转走。
        BusinessException aliceRenew = assertThrows(BusinessException.class,
                () -> harness.service.renew(env.id(), renewRequest("alice", "PT2M")));
        assertEquals(ErrorCode.LEASE_ALREADY_TERMINATED, aliceRenew.getCode());
        BusinessException aliceTransfer = assertThrows(BusinessException.class,
                () -> harness.service.transfer(env.id(), transfer("late-a", "alice", "bob", "teamB")));
        assertEquals(ErrorCode.LEASE_ALREADY_TERMINATED, aliceTransfer.getCode());
        BusinessException bobRenew = assertThrows(BusinessException.class,
                () -> harness.service.renew(env.id(), renewRequest("bob", "PT2M")));
        assertEquals(ErrorCode.NOT_OWNER, bobRenew.getCode(),
                "判定依据：转让未生效，bob 自始至终不是归属者");
        BusinessException bobTransfer = assertThrows(BusinessException.class,
                () -> harness.service.transfer(env.id(), transfer("late-b", "bob", "carol", "teamC")));
        assertEquals(ErrorCode.NOT_OWNER, bobTransfer.getCode());
        assertEquals(LeaseStatus.TERMINATED, harness.service.get(env.id()).status());
        // 被拒操作之后水位仍全部归零
        assertEquals(0L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.teamView("teamB").used());
    }

    /** 转让先线性化：恰好一次转让原地生效，之后原归属者的一切操作被 NOT_OWNER 挡下。 */
    private void assertTransferWonRace(LeaseView env, LeaseView finalState,
                                       ConcurrentLinkedQueue<LeaseView> transferOk,
                                       ConcurrentLinkedQueue<ErrorCode> rejects,
                                       AtomicInteger terminates, int provisionSuccessAtStart) {
        // 转让先胜出：第一个拿到锁的转让把环境转给 bob；此后其余 alice 的转让/终止全部 NOT_OWNER。
        assertEquals(LeaseStatus.READY, finalState.status());
        assertEquals(1, transferOk.size(),
                "判定依据：只有第一个线性化的转让能生效；易主后其余转让以 NOT_OWNER 被拒，"
                        + "绝不是“全部转让各成功一次”");
        LeaseView moved = transferOk.peek();
        assertEquals(env.id(), moved.id(), "判定依据：环境原地易主，id 不变（不重新供给）");
        assertEquals(env.endpoint(), moved.endpoint(), "判定依据：endpoint/实例不变");
        assertEquals(env.expiresAt(), moved.expiresAt(), "判定依据：转让不改变到期时间");
        assertEquals("bob", finalState.owner());
        assertEquals("teamB", finalState.team());
        assertTrue(rejects.stream().allMatch(c -> c == ErrorCode.NOT_OWNER),
                "判定依据：易主后原归属者的转让/终止全部 NOT_OWNER，实际="
                        + rejects.stream().distinct().toList());
        assertEquals(0, terminates.get(), "判定依据：原归属者的终止一次都不能生效");
        // 各级配额随归属一起迁移：源 owner/team 归零，目标 owner/team 各计 1
        assertEquals(1L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(1L, harness.ledger.teamView("teamB").used());
        assertEquals(0L, harness.ledger.teamView("teamA").used());
        assertEquals(1, harness.provisioner.getOccupiedCount(),
                "判定依据：资源槽仍是原来那一个，不换实例");
        assertEquals(0, harness.provisioner.getSuccessCount() - provisionSuccessAtStart,
                "判定依据：转让不触发重新供给");

        // 旧归属者后续操作被挡
        BusinessException aliceRenew = assertThrows(BusinessException.class,
                () -> harness.service.renew(env.id(), renewRequest("alice", "PT2M")));
        assertEquals(ErrorCode.NOT_OWNER, aliceRenew.getCode());
        BusinessException aliceTerminate = assertThrows(BusinessException.class,
                () -> harness.service.terminate(env.id(), "alice"));
        assertEquals(ErrorCode.NOT_OWNER, aliceTerminate.getCode());
        BusinessException aliceTransfer = assertThrows(BusinessException.class,
                () -> harness.service.transfer(env.id(), transfer("late-a2", "alice", "carol", "teamC")));
        assertEquals(ErrorCode.NOT_OWNER, aliceTransfer.getCode());
        // 归属与水位未被这些失败操作改动
        assertEquals("bob", harness.service.get(env.id()).owner());
        assertEquals(1L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.ownerView("carol").used());

        // 新归属者能正常续租（在原到期时间上延长），随后正常终止并释放全部配额与资源槽
        LeaseView renewed = harness.service.renew(env.id(), renewRequest("bob", "PT2M"));
        assertEquals("bob", renewed.owner());
        assertEquals(env.expiresAt().plus(java.time.Duration.ofMinutes(2)), renewed.expiresAt(),
                "判定依据：接手方在原到期时间基础上续租成功");
        LeaseView terminated = harness.service.terminate(env.id(), "bob");
        assertEquals(LeaseStatus.TERMINATED, terminated.status());
        assertEquals(0L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.teamView("teamB").used());
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(0, harness.provisioner.getOccupiedCount());
    }

    private static RenewRequest renewRequest(String operator, String extension) {
        RenewRequest r = new RenewRequest();
        r.setOperator(operator);
        r.setExtension(extension);
        return r;
    }

    @Test
    @DisplayName("转让 vs 到期回收同时发生：要么成功转走且仍 READY，要么原子回收且转让以 LEASE_ALREADY_EXPIRED 拒绝")
    void transferVsReapAtBoundary() throws Exception {
        LeaseView env = apply("race-exp", "alice", "teamA");
        harness.clock.set(Instant.parse("2026-09-22T00:10:00Z")); // 恰好在到期点
        int threads = 24;
        ConcurrentLinkedQueue<LeaseView> transferOk = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ErrorCode> rejects = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Integer> reaps = new ConcurrentLinkedQueue<>();

        raceOneLease(threads, env.id(), idx -> {
            try {
                if (idx % 2 == 0) {
                    transferOk.add(harness.service.transfer(env.id(),
                            transfer("race-exp-" + idx, "alice", "bob", "teamB")));
                } else {
                    reaps.add(harness.service.reapExpired());
                }
            } catch (BusinessException ex) {
                rejects.add(ex.getCode());
            }
        });

        LeaseView finalState = harness.service.get(env.id());
        long totalReaped = reaps.stream().mapToInt(Integer::intValue).sum();
        log.info("判定结果：终态={} 转让成功={} reap 翻转={} 拒绝码={}",
                finalState.status(), transferOk.size(), totalReaped, rejects.stream().distinct().toList());

        // 到期点 now>=expiresAt 恒成立：转让绝不可能成功，终态必须 EXPIRED。
        assertTrue(transferOk.isEmpty(), "判定依据：已结束环境绝不能被转走");
        assertEquals(LeaseStatus.EXPIRED, finalState.status());
        assertTrue(totalReaped <= 1, "判定依据：READY->EXPIRED 全局至多翻转一次");
        assertTrue(rejects.stream().allMatch(c -> c == ErrorCode.LEASE_ALREADY_EXPIRED),
                "判定依据：所有转让以明确的 LEASE_ALREADY_EXPIRED 拒绝，实际="
                        + rejects.stream().distinct().toList());
        assertEquals(0L, harness.ledger.ownerView("alice").used(),
                "判定依据：到期释放的是原 owner 配额（本场景无人接手成功）");
        assertEquals(0L, harness.ledger.ownerView("bob").used(),
                "判定依据：目标 owner 配额绝不能被一次失败转让占用（配额不被吞）");
        assertEquals(0L, harness.ledger.teamView("teamA").used());
        assertEquals(0L, harness.ledger.teamView("teamB").used(),
                "判定依据：目标 team 配额绝不能被一次失败转让占用（配额不被吞）");
        assertFalse(finalState.quotaHeld(), "判定依据：EXPIRED 后 quotaHeld 必须为 false");
        assertEquals(0, harness.provisioner.getOccupiedCount());
        assertEquals(1, harness.provisioner.getSuccessCount(),
                "判定依据：转让/回收路径都不重新供给，供给次数仍为初始 1 次");
    }

    @Test
    @DisplayName("边界前一刻转让 vs 续租 vs 回收：结论确定，续租/转让都只认当前归属者；被拒续租绝不延长环境")
    void transferVsRenewBeforeBoundary() throws Exception {
        LeaseView env = apply("race-ren", "alice", "teamA");
        harness.clock.set(Instant.parse("2026-09-22T00:09:59Z")); // 到期前 1 秒
        int threads = 24;
        ConcurrentLinkedQueue<LeaseView> transferOk = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> renewOkOperators = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> renewRejectOperators = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ErrorCode> rejects = new ConcurrentLinkedQueue<>();

        raceOneLease(threads, env.id(), idx -> {
            try {
                switch (idx % 3) {
                    case 0 -> transferOk.add(harness.service.transfer(env.id(),
                            transfer("race-ren-" + idx, "alice", "bob", "teamB")));
                    // 续租方不知道归属会变化：一半以 alice、一半以 bob 尝试，两者恰有一个在任何时刻是 owner
                    case 1 -> {
                        String operator = idx % 2 == 1 ? "alice" : "bob";
                        RenewRequest r = new RenewRequest();
                        r.setOperator(operator);
                        r.setExtension("PT1M");
                        try {
                            harness.service.renew(env.id(), r);
                            renewOkOperators.add(operator);
                        } catch (BusinessException ex) {
                            if (ex.getCode() == ErrorCode.NOT_OWNER) {
                                renewRejectOperators.add(operator);
                            }
                            throw ex;
                        }
                    }
                    default -> harness.service.reapExpired();
                }
            } catch (BusinessException ex) {
                rejects.add(ex.getCode());
            }
        });

        LeaseView finalState = harness.service.get(env.id());
        long aliceRenewOk = renewOkOperators.stream().filter("alice"::equals).count();
        long bobRenewOk = renewOkOperators.stream().filter("bob"::equals).count();
        long aliceRenewRejected = renewRejectOperators.stream().filter("alice"::equals).count();
        long bobRenewRejected = renewRejectOperators.stream().filter("bob"::equals).count();
        log.info("判定结果：终态={} owner={} 转让成功={} 续租成功(alice={},bob={}) "
                        + "续租NOT_OWNER(alice={},bob={}) 拒绝码={} expiresAt={}",
                finalState.status(), finalState.owner(), transferOk.size(),
                aliceRenewOk, bobRenewOk, aliceRenewRejected, bobRenewRejected,
                rejects.stream().distinct().toList(), finalState.expiresAt());

        // 未到期：回收不得翻转；所有 8 个转让都以 alice 身份发起，第一个线性化的转让必然成功，
        // 故最终 owner 确定为 bob、状态恒 READY。
        assertEquals(LeaseStatus.READY, finalState.status());
        assertEquals("bob", finalState.owner(),
                "判定依据：8 个转让均由当前唯一可转让者 alice 发起，第一个必然生效，归属确定 bob");
        assertEquals(1, transferOk.size(),
                "判定依据：只有第一个线性化的转让生效；其余转让（以及易主后的全部 alice 操作）NOT_OWNER");
        assertTrue(rejects.stream().allMatch(c -> c == ErrorCode.NOT_OWNER),
                "判定依据：未到期不会有过期类拒绝；所有拒绝只能来自归属校验，实际="
                        + rejects.stream().distinct().toList());

        // 续租账本（线程布局：8 个续租线程，alice/bob 各 4）：
        //  - alice 的 4 次续租：转让前 owner=alice 可能成功，转让后必 NOT_OWNER；
        //    成功 + 拒绝恒为 4，且任何成功只可能发生在转让之前。
        //  - bob 的 4 次续租：转让前必 NOT_OWNER，转让后成功；成功 + 拒绝恒为 4。
        assertEquals(4, aliceRenewOk + aliceRenewRejected, "判定依据：alice 续租总数守恒为 4");
        assertEquals(4, bobRenewOk + bobRenewRejected, "判定依据：bob 续租总数守恒为 4");
        assertEquals(8, renewOkOperators.size() + renewRejectOperators.size(),
                "判定依据：8 次续租要么成功要么 NOT_OWNER，无第三种结局");

        // 到期时间精确对账：被拒的续租绝不延长环境；每次成功续租在当前到期时间上叠加 1 分钟。
        // 初始 expiresAt=00:10；alice 的成功续租只可能在转让前，bob 的成功续租只可能在转让后，
        // 无论线性化细节如何，最终 expiresAt 严格等于 00:10 + 成功续租总次数。
        assertEquals(env.expiresAt().plus(java.time.Duration.ofMinutes(renewOkOperators.size())),
                finalState.expiresAt(),
                "判定依据：到期时间只能被成功续租推动，被 NOT_OWNER 拒绝的续租不得留下任何延长效果");

        // 两级配额与归属一致，资源槽仍是原来那一个
        assertEquals(1L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(1L, harness.ledger.teamView("teamB").used());
        assertEquals(0L, harness.ledger.teamView("teamA").used());
        assertEquals(1, harness.provisioner.getOccupiedCount());
        assertEquals(0, harness.provisioner.getSuccessCount() - 1,
                "判定依据：转让/续租/回收都不触发重新供给");
    }

    @Test
    @DisplayName("混合并发压测（申请/转让/续租/终止交错，时钟固定不制造过期）：压完水位与存活环境数逐 owner、逐 team 对得上")
    void mixedStressLedgerReconciliation() throws Exception {
        String[] owners = {"alice", "bob", "carol"};
        String[] teams = {"teamA", "teamB", "teamC"};
        int envCount = 12;
        for (int i = 0; i < envCount; i++) {
            apply("seed-" + i, owners[i % owners.length], teams[i % teams.length]);
        }

        int rounds = 600;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<ErrorCode> codes = new ConcurrentLinkedQueue<>();
        AtomicInteger seq = new AtomicInteger();

        for (int i = 0; i < rounds; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    int n = seq.incrementAndGet();
                    java.util.List<Lease> snapshot = new java.util.ArrayList<>(harness.repository.findAll());
                    Lease picked = snapshot.get(n % snapshot.size());
                    int op = n % 5;
                    if (op == 0) {
                        try {
                            harness.service.terminate(picked.getId(), picked.getOwner());
                        } catch (BusinessException ex) {
                            codes.add(ex.getCode());
                        }
                    } else if (op == 1) {
                        String to = owners[n % owners.length];
                        String tt = teams[n % teams.length];
                        try {
                            // 独立幂等键，避免同键冲突干扰本用例（键冲突由专门用例覆盖）
                            harness.service.transfer(picked.getId(),
                                    transfer("stress-" + n + "-" + java.util.UUID.randomUUID(),
                                            picked.getOwner(), to, tt));
                        } catch (BusinessException ex) {
                            codes.add(ex.getCode());
                        }
                    } else if (op == 2) {
                        RenewRequest r = new RenewRequest();
                        r.setOperator(picked.getOwner());
                        r.setExtension("PT1M");
                        try {
                            harness.service.renew(picked.getId(), r);
                        } catch (BusinessException ex) {
                            codes.add(ex.getCode());
                        }
                    } else if (op == 3) {
                        // 并发新申请：检验转让与申请同时争抢配额时仍精确守恒
                        apply("apply-stress-" + n, owners[n % owners.length],
                                teams[(n + 1) % teams.length]);
                    } else {
                        // 到期回收入口在时钟固定、全部未到期时必须为空操作
                        int reaped = harness.service.reapExpired();
                        assertEquals(0, reaped, "判定依据：时钟固定在起点，任何环境都未到期");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        // 最终对账：账本水位 == 当前归属名下 quotaHeld 环境数（逐 owner、逐 team）
        for (String owner : owners) {
            long live = harness.repository.findAll().stream()
                    .filter(Lease::isQuotaHeld)
                    .filter(l -> owner.equals(l.getOwner()))
                    .count();
            long used = harness.ledger.ownerView(owner).used();
            assertEquals(live, used, "判定依据：owner=" + owner + " 水位须等于存活环境数");
        }
        for (String team : teams) {
            long live = harness.repository.findAll().stream()
                    .filter(Lease::isQuotaHeld)
                    .filter(l -> team.equals(l.getTeam()))
                    .count();
            long used = harness.ledger.teamView(team).used();
            assertEquals(live, used, "判定依据：team=" + team + " 水位须等于存活环境数");
        }
        int heldSlots = harness.provisioner.getOccupiedCount();
        long liveTotal = harness.repository.findAll().stream().filter(Lease::isQuotaHeld).count();
        assertEquals(liveTotal, heldSlots,
                "判定依据：供给资源槽数量也与存活环境数一致（无泄漏/无凭空占用）");
        log.info("混合压测 {} 轮结束：存活环境={} 拒绝码分布={}",
                rounds, liveTotal, codes.stream().collect(java.util.stream.Collectors.groupingBy(
                        c -> c, java.util.stream.Collectors.counting())));
    }
}
