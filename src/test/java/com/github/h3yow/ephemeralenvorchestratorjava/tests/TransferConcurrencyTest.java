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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 转让与终止 / 续租 / 到期回收并发。
 *
 * <p>设计原则：
 * <ul>
 *   <li>全部入口经同一把全局锁串行裁决，<b>结论只有一个</b>，但谁先拿到锁由调度决定，
 *       因此竞态用例分两层覆盖：① 同一起跑线的真实并发（多轮，覆盖各种交错），
 *       断言两种胜出顺序下<b>各自必须成立的全部结论</b>（不放松断言）；
 *       ② 分阶段（phased）确定性用例，用栅栏把“谁的临界区先提交”钉死，
 *       保证每种胜出顺序都被<b>稳定复现</b>，不依赖线程调度运气；</li>
 *   <li>成功路径真验证：环境原地易主（id/endpoint/资源槽/到期时间不变）、归属与两级配额一起迁移、
 *       新归属者随后可续租可终止、原归属者被挡；</li>
 *   <li>失败路径真验证：各方水位纹丝不动（源不释放、目标不占用）、已结束环境不被转走、
 *       资源槽不泄漏。</li>
 * </ul>
 */
class TransferConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(TransferConcurrencyTest.class);

    private TestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new TestHarness(20L, 200L);
    }

    private ApplyLeaseRequest applyReq(String requestId, String owner, String team) {
        ApplyLeaseRequest r = new ApplyLeaseRequest();
        r.setRequestId(requestId);
        r.setOwner(owner);
        r.setTeam(team);
        r.setTtl("PT10M");
        return r;
    }

    private LeaseView apply(String requestId, String owner, String team) {
        return harness.service.apply(applyReq(requestId, owner, team));
    }

    private TransferRequest transfer(String requestId, String operator, String owner, String team) {
        TransferRequest r = new TransferRequest();
        r.setRequestId(requestId);
        r.setOperator(operator);
        r.setTargetOwner(owner);
        r.setTargetTeam(team);
        return r;
    }

    private RenewRequest renew(String operator, String extension) {
        RenewRequest r = new RenewRequest();
        r.setOperator(operator);
        r.setExtension(extension);
        return r;
    }

    /** 让一批任务在同一道起跑线后并发执行；任何未捕获异常都会让用例失败（断言不能被工作线程吞掉）。 */
    private void race(int threads, ThrowingTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, threads));
        try {
            CountDownLatch start = new CountDownLatch(1);
            ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        task.run(idx);
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务未在超时内结束");
            if (!failures.isEmpty()) {
                failures.forEach(t -> log.error("并发工作线程异常", t));
                throw new AssertionError("并发工作线程出现 " + failures.size() + " 个异常", failures.peek());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingTask {
        void run(int idx) throws Exception;
    }

    // ------------------------------------------------ 转让 vs 终止

    /**
     * 真实并发：12 转让 + 12 终止同一起跑。多轮、每轮全新环境，覆盖两种调度胜出顺序。
     */
    @Test
    @DisplayName("转让 vs 终止同时发生（多轮真实并发）：二选一确定胜出，强校验两种胜出顺序的完整结论")
    void transferVsTerminateRace() throws Exception {
        int rounds = 24;
        int threads = 24;
        int sawTransferWins = 0;
        int sawTerminateWins = 0;

        for (int round0 = 0; round0 < rounds; round0++) {
            final int round = round0;
            // 每轮一个全新夹具：账本/资源槽计数从零开始，避免跨轮污染，便于逐轮精确对账
            TestHarness h = new TestHarness(20L, 200L);
            LeaseView env = h.service.apply(applyReq("race-t-" + round, "alice", "teamA"));
            ConcurrentLinkedQueue<LeaseView> transferOk = new ConcurrentLinkedQueue<>();
            // 转让侧与终止侧的拒绝分开统计：重复终止也会产生终态码，混在一起无法精确断言
            ConcurrentLinkedQueue<ErrorCode> transferRejects = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<ErrorCode> terminateRejects = new ConcurrentLinkedQueue<>();
            AtomicInteger terminates = new AtomicInteger();

            race(threads, idx -> {
                try {
                    if (idx % 2 == 0) {
                        transferOk.add(h.service.transfer(env.id(),
                                transfer("race-t-" + round + "-" + idx, "alice", "bob", "teamB")));
                    } else {
                        LeaseView v = h.service.terminate(env.id(), "alice");
                        if (v.status() == LeaseStatus.TERMINATED) {
                            terminates.incrementAndGet();
                        }
                    }
                } catch (BusinessException ex) {
                    if (idx % 2 == 0) {
                        transferRejects.add(ex.getCode());
                    } else {
                        terminateRejects.add(ex.getCode());
                    }
                }
            });

            LeaseView before = env;
            LeaseView finalState = h.service.get(env.id());
            boolean terminatedFirst = finalState.status() == LeaseStatus.TERMINATED;
            if (terminatedFirst) {
                sawTerminateWins++;
            } else {
                sawTransferWins++;
            }

            // ---- 两种胜出顺序共同的不变量 ----
            assertTrue(transferRejects.stream().allMatch(c ->
                            c == ErrorCode.LEASE_ALREADY_TERMINATED || c == ErrorCode.NOT_OWNER),
                    "转让侧只可能出现终态拒绝或归属拒绝，实际=" + transferRejects.stream().distinct().toList());

            if (terminatedFirst) {
                // ===== 终止先提交：12 个转让全部被终态码拒绝，环境保持终止，配额全归零 =====
                assertTrue(transferOk.isEmpty(), "判定依据：已结束环境绝不能被转走");
                assertEquals(12, transferRejects.size(), "判定依据：12 个转让无一成功");
                assertTrue(transferRejects.stream().allMatch(c -> c == ErrorCode.LEASE_ALREADY_TERMINATED),
                        "判定依据：全部转让以 LEASE_ALREADY_TERMINATED 拒绝，实际="
                                + transferRejects.stream().distinct().toList());
                // 首个终止生效，其余 11 个重复终止各自拿到 LEASE_ALREADY_TERMINATED
                assertEquals(1, terminates.get(), "判定依据：READY->TERMINATED 全局至多翻转一次");
                assertTrue(terminateRejects.stream().allMatch(c -> c == ErrorCode.LEASE_ALREADY_TERMINATED),
                        "判定依据：重复终止拿到终态码，实际=" + terminateRejects.stream().distinct().toList());
                assertEquals(11, terminateRejects.size());
                assertEquals(0L, h.ledger.ownerView("alice").used());
                assertEquals(0L, h.ledger.ownerView("bob").used(),
                        "判定依据：转让未成功则目标 owner 配额绝不能被占用（不被吞）");
                assertEquals(0L, h.ledger.teamView("teamA").used());
                assertEquals(0L, h.ledger.teamView("teamB").used(),
                        "判定依据：转让未成功则目标 team 配额绝不能被占用（不被吞）");
                assertEquals(0, h.provisioner.getOccupiedCount(),
                        "判定依据：终止已回收唯一资源槽，不泄漏");
            } else {
                // ===== 转让先提交：仅第一个拿到锁的转让成功（alice->bob），
                //       其余 11 个 alice 转让与 12 个 alice 终止全部 NOT_OWNER =====
                assertEquals(LeaseStatus.READY, finalState.status());
                assertEquals(1, transferOk.size(),
                        "判定依据：环境只能原地易主一次；第一次转让生效后，其余 alice 转让必为 NOT_OWNER，"
                                + "实际成功=" + transferOk.size());
                LeaseView moved = transferOk.iterator().next();
                // 成功路径真验证：环境原地易主，实例与到期时间不变
                assertEquals(before.id(), moved.id());
                assertEquals(before.endpoint(), moved.endpoint(), "判定依据：不换实例，endpoint 不变");
                assertEquals(before.expiresAt(), moved.expiresAt(), "判定依据：到期时间不因转让变化");
                assertEquals("bob", moved.owner());
                assertEquals("teamB", moved.team());
                assertTrue(moved.quotaHeld());
                assertEquals(11, transferRejects.size(), "判定依据：其余 11 个转让全部失败");
                assertTrue(transferRejects.stream().allMatch(c -> c == ErrorCode.NOT_OWNER),
                        "判定依据：易主后其余 alice 转让全部 NOT_OWNER，实际="
                                + transferRejects.stream().distinct().toList());
                assertTrue(terminateRejects.stream().allMatch(c -> c == ErrorCode.NOT_OWNER),
                        "判定依据：12 个 alice 终止全部被 NOT_OWNER 挡下，实际="
                                + terminateRejects.stream().distinct().toList());
                assertEquals(12, terminateRejects.size());
                assertEquals(0, terminates.get(), "判定依据：归属已变，没有任何一次 alice 终止能生效");
                // 两级配额随归属一起迁移
                assertEquals(0L, h.ledger.ownerView("alice").used());
                assertEquals(1L, h.ledger.ownerView("bob").used());
                assertEquals(0L, h.ledger.teamView("teamA").used());
                assertEquals(1L, h.ledger.teamView("teamB").used());
                assertEquals(1, h.provisioner.getOccupiedCount(), "判定依据：仍是原来那个资源槽");
                assertEquals(1, h.provisioner.getSuccessCount(), "判定依据：转让不触发重新供给");
                // 赛后收尾：新 owner 可正常终止（顺带验证操作面 + 释放本轮夹具资源）
                assertEquals(LeaseStatus.TERMINATED, h.service.terminate(env.id(), "bob").status());
                assertEquals(0, h.provisioner.getOccupiedCount());
            }
            log.info("第 {} 轮胜出方={} 转让成功={} 终止成功={}", round,
                    terminatedFirst ? "终止" : "转让", transferOk.size(), terminates.get());
        }

        // 多轮下两种调度顺序都真实出现过：确保每条分支断言都被执行到，而不是靠调度运气漏检
        assertTrue(sawTerminateWins > 0, "多轮并发中应至少出现一次『终止先胜出』，实际=" + sawTerminateWins);
        assertTrue(sawTransferWins > 0, "多轮并发中应至少出现一次『转让先胜出』，实际=" + sawTransferWins);
        log.info("转让 vs 终止 {} 轮：转让先胜 {} 轮，终止先胜 {} 轮", rounds, sawTransferWins, sawTerminateWins);
    }

    /**
     * 确定性顺序 A：先终止提交完成，再发起转让。转让必须 LEASE_ALREADY_TERMINATED，
     * 归属/两级配额/资源槽全部停留在终止后的归零状态。
     */
    @Test
    @DisplayName("确定性顺序 A：终止先落定，随后转让被 LEASE_ALREADY_TERMINATED 拒绝且配额不被吞")
    void terminateCommitsFirst_thenTransferRejected() {
        LeaseView env = apply("order-a", "alice", "teamA");
        assertEquals(LeaseStatus.TERMINATED, harness.service.terminate(env.id(), "alice").status());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> harness.service.transfer(env.id(), transfer("order-a-tr", "alice", "bob", "teamB")));
        assertEquals(ErrorCode.LEASE_ALREADY_TERMINATED, ex.getCode());

        LeaseView view = harness.service.get(env.id());
        assertEquals(LeaseStatus.TERMINATED, view.status());
        assertEquals("alice", view.owner(), "判定依据：被拒转让不得改写归属");
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(0L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.teamView("teamA").used());
        assertEquals(0L, harness.ledger.teamView("teamB").used());
        assertEquals(0, harness.provisioner.getOccupiedCount());
        log.info("确定性顺序 A 通过：先终止后转让 -> {}", ex.getCode());
    }

    /**
     * 确定性顺序 B：转让先提交完成（alice->bob），再由两人分别终止。
     * 原 owner 终止被 NOT_OWNER 挡下；新 owner 终止成功且只释放新归属两边配额。
     */
    @Test
    @DisplayName("确定性顺序 B：转让先落定，原归属者终止被 NOT_OWNER 挡，新归属者终止成功并释放配额")
    void transferCommitsFirst_thenOnlyNewOwnerCanTerminate() {
        LeaseView env = apply("order-b", "alice", "teamA");
        LeaseView moved = harness.service.transfer(env.id(),
                transfer("order-b-tr", "alice", "bob", "teamB"));
        assertEquals("bob", moved.owner());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> harness.service.terminate(env.id(), "alice"));
        assertEquals(ErrorCode.NOT_OWNER, ex.getCode(), "判定依据：旧归属者后续操作被挡");
        assertEquals(LeaseStatus.READY, harness.service.get(env.id()).status(),
                "判定依据：被挡的终止不得改变状态");

        LeaseView terminated = harness.service.terminate(env.id(), "bob");
        assertEquals(LeaseStatus.TERMINATED, terminated.status());
        assertEquals(0L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.teamView("teamB").used());
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(0L, harness.ledger.teamView("teamA").used());
        assertEquals(0, harness.provisioner.getOccupiedCount());
        log.info("确定性顺序 B 通过：先转让后，alice 终止被挡，bob 终止成功");
    }

    /**
     * 交错窗口：转让线程已进入服务但在其临界区提交前，终止先一步拿到锁完成；
     * 随后转让提交，必须看到终态并以 LEASE_ALREADY_TERMINATED 失败。
     * 用“终止完成状态可见”作为放行栅栏，把提交顺序钉死，结果与调度无关、可稳定复现。
     */
    @Test
    @DisplayName("交错复现：终止临界区先提交时，正在门口等待的转让拿到 LEASE_ALREADY_TERMINATED")
    void interleavedTerminateCommitsBeforeWaitingTransfer() throws Exception {
        LeaseView env = apply("interleave-t", "alice", "teamA");
        CountDownLatch terminateDone = new CountDownLatch(1);
        AtomicReference<Throwable> transferErrorRef = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 转让线程先启动并阻塞在栅栏前，终止线程拿到锁完成后再放行转让
            Future<?> transferFuture = pool.submit(() -> {
                try {
                    terminateDone.await();
                    harness.service.transfer(env.id(),
                            transfer("interleave-t-tr", "alice", "bob", "teamB"));
                } catch (BusinessException ex) {
                    transferErrorRef.set(ex);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Future<?> terminateFuture = pool.submit(() -> {
                harness.service.terminate(env.id(), "alice");
                terminateDone.countDown();
            });
            terminateFuture.get(10, TimeUnit.SECONDS);
            transferFuture.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        Throwable t = transferErrorRef.get();
        assertNotNull(t, "判定依据：转让必须失败而不是悄悄成功");
        assertInstanceOf(BusinessException.class, t);
        assertEquals(ErrorCode.LEASE_ALREADY_TERMINATED, ((BusinessException) t).getCode());
        assertEquals(0L, harness.ledger.ownerView("bob").used());
        assertEquals(0, harness.provisioner.getOccupiedCount());
        log.info("交错复现通过：等待中的转让在终止提交后拿到 LEASE_ALREADY_TERMINATED");
    }

    // ------------------------------------------------ 转让 vs 到期回收

    @Test
    @DisplayName("转让 vs 到期回收同时发生：到期点上只能原子回收，所有转让以 LEASE_ALREADY_EXPIRED 拒绝")
    void transferVsReapAtBoundaryRace() throws Exception {
        LeaseView env = apply("race-exp", "alice", "teamA");
        harness.clock.set(Instant.parse("2026-09-22T00:10:00Z")); // 恰好在到期点（now >= expiresAt）
        int threads = 24;
        ConcurrentLinkedQueue<LeaseView> transferOk = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ErrorCode> rejects = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Integer> reaps = new ConcurrentLinkedQueue<>();

        race(threads, idx -> {
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

        // 到期点 now>=expiresAt 恒成立：任何调度顺序下转让都绝不可能成功，终态必须 EXPIRED。
        assertTrue(transferOk.isEmpty(), "判定依据：已结束环境绝不能被转走");
        assertEquals(LeaseStatus.EXPIRED, finalState.status());
        assertTrue(totalReaped <= 1, "判定依据：READY->EXPIRED 全局至多翻转一次");
        assertEquals(12, rejects.size(), "判定依据：12 个转让全部被拒绝");
        assertTrue(rejects.stream().allMatch(c -> c == ErrorCode.LEASE_ALREADY_EXPIRED),
                "判定依据：所有转让以明确的 LEASE_ALREADY_EXPIRED 拒绝，实际="
                        + rejects.stream().distinct().toList());
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(0L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.teamView("teamA").used());
        assertEquals(0L, harness.ledger.teamView("teamB").used(), "判定依据：配额未被转让吞掉");
        assertEquals(0, harness.provisioner.getOccupiedCount(), "判定依据：资源槽已随原子回收释放");
    }

    /**
     * 确定性顺序：未到期时先转让成功，随后时钟走到期并回收扫描。
     * 释放的必须是<b>新归属</b>两边的配额（若搬运没生效，这里会对不上）；已结束后再转让被终态码拒绝。
     */
    @Test
    @DisplayName("确定性顺序：先转让成功，后到期回收——释放新归属配额；EXPIRED 后再转让被拒")
    void transferFirst_thenReapReleasesNewOwnerQuota() {
        LeaseView env = apply("order-exp", "alice", "teamA");
        LeaseView moved = harness.service.transfer(env.id(),
                transfer("order-exp-tr", "alice", "bob", "teamB"));
        assertEquals("bob", moved.owner());
        assertEquals(Instant.parse("2026-09-22T00:10:00Z"), moved.expiresAt());

        harness.clock.set(Instant.parse("2026-09-22T00:10:00Z"));
        assertEquals(1, harness.service.reapExpired(), "判定依据：到期点恰好回收一条");

        LeaseView view = harness.service.get(env.id());
        assertEquals(LeaseStatus.EXPIRED, view.status());
        assertEquals("bob", view.owner(), "判定依据：回收不改写归属");
        assertEquals(0L, harness.ledger.ownerView("bob").used(), "判定依据：释放的是新 owner 配额");
        assertEquals(0L, harness.ledger.teamView("teamB").used(), "判定依据：释放的是新 team 配额");
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(0L, harness.ledger.teamView("teamA").used());
        assertEquals(0, harness.provisioner.getOccupiedCount());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> harness.service.transfer(env.id(), transfer("order-exp-tr2", "bob", "carol", "teamC")));
        assertEquals(ErrorCode.LEASE_ALREADY_EXPIRED, ex.getCode(),
                "判定依据：已结束环境绝不能被转走，即使新 owner 发起也不行");
        assertEquals(0L, harness.ledger.ownerView("carol").used());
        log.info("确定性顺序通过：先转让后回收，释放 bob/teamB 配额，EXPIRED 后转让 -> {}", ex.getCode());
    }

    // ------------------------------------------------ 转让 vs 续租

    /**
     * 三方真实并发：8 个 alice 转让 + 8 个续租（alice/bob 各半）+ 8 个回收，
     * 时钟固定在到期前 1 秒。结论确定：恒 READY、恰有一次转让生效到 bob、
     * 只有当前 owner 的续租成功、所有拒绝都是 NOT_OWNER；赛后新 owner 续租/终止正常。
     */
    @Test
    @DisplayName("边界前一刻转让 vs 续租 vs 回收：结论确定，续租只认当前归属者，赛后新归属者可续租终止")
    void transferVsRenewBeforeBoundaryRace() throws Exception {
        LeaseView env = apply("race-ren", "alice", "teamA");
        harness.clock.set(Instant.parse("2026-09-22T00:09:59Z")); // 到期前 1 秒
        int threads = 24;
        ConcurrentLinkedQueue<LeaseView> transferOk = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<LeaseView> renewOk = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ErrorCode> rejects = new ConcurrentLinkedQueue<>();

        race(threads, idx -> {
            try {
                switch (idx % 3) {
                    case 0, 3, 6, 9, 12, 15, 18, 21 -> transferOk.add(
                            harness.service.transfer(env.id(),
                                    transfer("race-ren-" + idx, "alice", "bob", "teamB")));
                    // 续租方不知道归属何时变化：一半以 alice、一半以 bob 尝试，任何时刻恰有一个是 owner
                    case 1, 4, 7, 10, 13, 16, 19, 22 -> renewOk.add(
                            harness.service.renew(env.id(),
                                    renew((idx / 3) % 2 == 0 ? "alice" : "bob", "PT1M")));
                    default -> harness.service.reapExpired();
                }
            } catch (BusinessException ex) {
                rejects.add(ex.getCode());
            }
        });

        LeaseView finalState = harness.service.get(env.id());
        log.info("判定结果：终态={} owner={} 转让成功={} 续租成功={} 拒绝码={} expiresAt={}",
                finalState.status(), finalState.owner(), transferOk.size(), renewOk.size(),
                rejects.stream().distinct().toList(), finalState.expiresAt());

        // 未到期：回收不得翻转；8 个 alice 转让中恰有第一个生效，其余全部 NOT_OWNER
        assertEquals(LeaseStatus.READY, finalState.status());
        assertEquals("bob", finalState.owner(),
                "判定依据：转让一旦拿到锁就必然生效（目标配额未满、未到期），最终归属确定为 bob");
        assertEquals(1, transferOk.size(),
                "判定依据：环境只能易主一次，实际成功=" + transferOk.size());
        LeaseView moved = transferOk.iterator().next();
        assertEquals(env.id(), moved.id());
        assertEquals(env.endpoint(), moved.endpoint(), "判定依据：原地易主不换实例");
        assertTrue(rejects.stream().allMatch(c -> c == ErrorCode.NOT_OWNER),
                "判定依据：未到期不会有过期类/终态类拒绝；所有拒绝只能来自归属校验，实际="
                        + rejects.stream().distinct().toList());
        // 两级配额随归属一起迁移
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(1L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.teamView("teamA").used());
        assertEquals(1L, harness.ledger.teamView("teamB").used());
        assertEquals(1, harness.provisioner.getOccupiedCount());
        // 成功的续租必须真正顺延了到期时间：每次都在“当前到期时间”上叠加 1 分钟，
        // 因此最终到期时刻 = 原到期时间 + 成功续租分钟数（次数随调度变化，但结论唯一可复现）
        Instant baseExpiry = Instant.parse("2026-09-22T00:10:00Z");
        assertTrue(renewOk.size() >= 1, "判定依据：至少有一次当前 owner 的续租成功");
        for (LeaseView v : renewOk) {
            assertTrue(v.expiresAt().isAfter(baseExpiry),
                    "判定依据：每次成功续租都确实顺延了到期时间，view=" + v.expiresAt());
        }
        assertEquals(baseExpiry.plus(Duration.ofMinutes(renewOk.size())), finalState.expiresAt(),
                "判定依据：成功续租次数与最终到期时间严格对应（" + renewOk.size() + " 次）");

        // 赛后操作面验证：新归属者可续租、可终止；旧归属者两者皆被挡
        LeaseView renewedByBob = harness.service.renew(env.id(), renew("bob", "PT1M"));
        assertEquals(finalState.expiresAt().plus(Duration.ofMinutes(1)), renewedByBob.expiresAt());
        assertEquals("bob", renewedByBob.owner());
        BusinessException oldOwner = assertThrows(BusinessException.class,
                () -> harness.service.renew(env.id(), renew("alice", "PT1M")));
        assertEquals(ErrorCode.NOT_OWNER, oldOwner.getCode());
        LeaseView terminated = harness.service.terminate(env.id(), "bob");
        assertEquals(LeaseStatus.TERMINATED, terminated.status());
        assertEquals(0L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.teamView("teamB").used());
        assertEquals(0, harness.provisioner.getOccupiedCount());
    }

    // ------------------------------------------- 转让失败时的配额守恒（并发）

    /**
     * 目标 owner 配额已满时，转让与“占满配额的目标 owner 的操作”并发：
     * 所有转让都必须失败，源水位不释放、目标水位不增加，被拒不消费幂等键。
     */
    @Test
    @DisplayName("并发：目标 owner 配额已满时转让必败，源/目标两边水位在交错下始终守恒，键可随后复用")
    void failedTransferConservesQuotaUnderConcurrency() throws Exception {
        // 专用夹具：owner 配额=2，便于把目标 owner 真正填满（类级夹具配额是 20）
        TestHarness h = new TestHarness(2L, 200L);
        // bob 先占满 owner 配额（2/2）
        LeaseView bob1 = h.service.apply(applyReq("q-bob-1", "bob", "teamX"));
        h.service.apply(applyReq("q-bob-2", "bob", "teamX"));
        LeaseView env = h.service.apply(applyReq("q-env", "alice", "teamA"));
        assertEquals(2L, h.ledger.ownerView("bob").used());
        assertEquals(1L, h.ledger.ownerView("alice").used());

        int threads = 16;
        ConcurrentLinkedQueue<ErrorCode> rejects = new ConcurrentLinkedQueue<>();
        // 交错加入 bob 的查询/配额查询，制造账本热点，但不能影响计数
        race(threads, idx -> {
            try {
                if (idx % 2 == 0) {
                    h.service.transfer(env.id(),
                            transfer("q-tr-" + idx, "alice", "bob", "teamB"));
                } else {
                    h.service.ownerQuota("bob");
                    h.service.get(env.id());
                }
            } catch (BusinessException ex) {
                rejects.add(ex.getCode());
            }
        });

        assertEquals(threads / 2, rejects.size(), "判定依据：8 次转让全部失败");
        assertTrue(rejects.stream().allMatch(c -> c == ErrorCode.OWNER_QUOTA_EXCEEDED),
                "判定依据：只能是目标 owner 配额不足，实际=" + rejects.stream().distinct().toList());
        assertEquals(1L, h.ledger.ownerView("alice").used(), "判定依据：源未释放");
        assertEquals(2L, h.ledger.ownerView("bob").used(), "判定依据：目标未被占用（配额不被吞）");
        assertEquals(1L, h.ledger.teamView("teamA").used());
        assertEquals(0L, h.ledger.teamView("teamB").used());
        assertEquals(LeaseStatus.READY, h.service.get(env.id()).status());
        assertEquals("alice", h.service.get(env.id()).owner());
        assertEquals(3, h.provisioner.getOccupiedCount());

        // 被拒不消费幂等键：同一个键在 bob 释放配额后可成功转让
        h.service.terminate(bob1.id(), "bob"); // bob 腾出一个 owner 槽
        LeaseView moved = h.service.transfer(env.id(), transfer("q-tr-0", "alice", "bob", "teamB"));
        assertEquals("bob", moved.owner());
        assertEquals(2L, h.ledger.ownerView("bob").used());
        assertEquals(0L, h.ledger.ownerView("alice").used());
        log.info("失败转让并发守恒通过：被拒不占键、不吞配额，腾槽后同键转让成功");
    }

    /**
     * 转让失败（目标配额满）与终止并发打在同一条环境上：
     * 无论失败转让在终止前还是后执行，源环境要么原样存活（配额仍在 alice），
     * 要么被 alice 终止归零；两种结论下 bob 都绝不能凭空多出配额，且总账守恒。
     */
    @Test
    @DisplayName("并发：失败转让（目标配额满）与终止交错——目标永不被占用，源状态与水位自洽")
    void failedTransferVsTerminateRace() throws Exception {
        int rounds = 20;
        for (int round0 = 0; round0 < rounds; round0++) {
            final int round = round0;
            // 每轮独立夹具：owner 配额=2，bob 占满后对 alice 环境的转让在终止前必败
            TestHarness h = new TestHarness(2L, 200L);
            h.service.apply(applyReq("ft-bob-" + round, "bob", "teamX"));
            h.service.apply(applyReq("ft-bob2-" + round, "bob", "teamX")); // bob owner 2/2 满
            LeaseView env = h.service.apply(applyReq("ft-env-" + round, "alice", "teamA"));

            int threads = 12;
            // 转让侧在终止前拿 OWNER_QUOTA_EXCEEDED；终止先落定后拿 LEASE_ALREADY_TERMINATED，
            // 两种都是合法的“转让失败”，但成功（transferOk）任何顺序下都不允许出现
            ConcurrentLinkedQueue<ErrorCode> transferRejects = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<LeaseView> transferOk = new ConcurrentLinkedQueue<>();
            AtomicInteger terminated = new AtomicInteger();
            race(threads, idx -> {
                try {
                    if (idx % 2 == 0) {
                        transferOk.add(h.service.transfer(env.id(),
                                transfer("ft-tr-" + round + "-" + idx, "alice", "bob", "teamB")));
                    } else {
                        LeaseView v = h.service.terminate(env.id(), "alice");
                        if (v.status() == LeaseStatus.TERMINATED) {
                            terminated.incrementAndGet();
                        }
                    }
                } catch (BusinessException ex) {
                    if (idx % 2 == 0) {
                        transferRejects.add(ex.getCode());
                    }
                }
            });

            LeaseView finalState = h.service.get(env.id());
            // 6 次转让在任何顺序下都必败，只能是“配额满”或“已终止”两个确定原因之一
            assertTrue(transferOk.isEmpty(), "判定依据：目标满额的环境在任何交错顺序下都不能被转走");
            assertEquals(6, transferRejects.size(),
                    "判定依据：第 " + round + " 轮 6 次转让全部失败，实际=" + transferRejects);
            assertTrue(transferRejects.stream().allMatch(c ->
                            c == ErrorCode.OWNER_QUOTA_EXCEEDED || c == ErrorCode.LEASE_ALREADY_TERMINATED),
                    "判定依据：失败原因只能是配额满或已终止，实际=" + transferRejects.stream().distinct().toList());
            if (finalState.status() == LeaseStatus.TERMINATED) {
                assertEquals(1, terminated.get());
                assertEquals(0L, h.ledger.ownerView("alice").used(),
                        "判定依据：终止生效，源配额归零");
                assertEquals(0L, h.ledger.teamView("teamA").used());
            } else {
                assertEquals(LeaseStatus.READY, finalState.status());
                assertEquals(0, terminated.get(), "判定依据：未终止就不能出现成功终止计数");
                assertEquals("alice", finalState.owner());
                assertEquals(1L, h.ledger.ownerView("alice").used(),
                        "判定依据：失败转让不释放源配额，环境仍存活");
                assertEquals(1L, h.ledger.teamView("teamA").used());
            }
            // 任何顺序下 bob 都只能保留自己的 2 个环境，绝不会吞到这条
            assertEquals(2L, h.ledger.ownerView("bob").used(),
                    "判定依据：失败转让绝不占用目标 owner 配额");
            assertEquals(0L, h.ledger.teamView("teamB").used(),
                    "判定依据：失败转让绝不占用目标 team 配额");
        }
        log.info("失败转让 vs 终止 {} 轮交错全部守恒", rounds);
    }

    // ----------------------------------------------------------- 混合压测

    @Test
    @DisplayName("混合并发压测（申请/转让/续租/终止/回收交错，时钟固定不制造过期）：压完水位与存活环境数逐 owner、逐 team 对得上")
    void mixedStressLedgerReconciliation() throws Exception {
        String[] owners = {"alice", "bob", "carol"};
        String[] teams = {"teamA", "teamB", "teamC"};
        int envCount = 12;
        for (int i = 0; i < envCount; i++) {
            apply("seed-" + i, owners[i % owners.length], teams[i % teams.length]);
        }

        int rounds = 600;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch start = new CountDownLatch(1);
            ConcurrentLinkedQueue<ErrorCode> codes = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
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
                            // 并发新申请：检验转让与申请同时争抢配额时仍精确守恒（被拒是合法业务结论）
                            try {
                                apply("apply-stress-" + n, owners[n % owners.length],
                                        teams[(n + 1) % teams.length]);
                            } catch (BusinessException ex) {
                                codes.add(ex.getCode());
                            }
                        } else {
                            // 到期回收入口在时钟固定、全部未到期时必须为空操作；
                            // 计数在主线程断言，绝不能在工作线程里 assertEquals（异常会被吞）
                            int reaped = harness.service.reapExpired();
                            if (reaped != 0) {
                                throw new AssertionError("时钟固定在起点，任何环境都未到期，却回收了 " + reaped + " 条");
                            }
                        }
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
            if (!failures.isEmpty()) {
                failures.forEach(t -> log.error("混合压测工作线程异常", t));
                throw new AssertionError("混合压测工作线程出现异常", failures.peek());
            }

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
        } finally {
            pool.shutdownNow();
        }
    }
}
