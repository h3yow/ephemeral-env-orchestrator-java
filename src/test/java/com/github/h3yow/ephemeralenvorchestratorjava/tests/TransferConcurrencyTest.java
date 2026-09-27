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
        LeaseView env = apply("race-t", "alice", "teamA");
        int threads = 24;
        ConcurrentLinkedQueue<LeaseView> transferOk = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ErrorCode> rejects = new ConcurrentLinkedQueue<>();
        AtomicInteger terminates = new AtomicInteger();

        raceOneLease(threads, env.id(), idx -> {
            try {
                if (idx % 2 == 0) {
                    transferOk.add(harness.service.transfer(env.id(),
                            transfer("race-t-" + idx, "alice", "bob", "teamB")));
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
        log.info("判定结果：终态={} 转让成功线程数={} 终止成功次数={} 拒绝码={}",
                finalState.status(), transferOk.size(), terminates.get(),
                rejects.stream().distinct().toList());

        if (finalState.status() == LeaseStatus.TERMINATED) {
            // 终止先胜出：所有转让必须被拒，且只能是 LEASE_ALREADY_TERMINATED
            assertTrue(transferOk.isEmpty(), "判定依据：已结束环境绝不能被转走");
            assertTrue(rejects.stream().allMatch(c -> c == ErrorCode.LEASE_ALREADY_TERMINATED),
                    "实际=" + rejects.stream().distinct().toList());
            assertEquals(1, terminates.get());
            assertEquals(0L, harness.ledger.ownerView("alice").used());
            assertEquals(0L, harness.ledger.ownerView("bob").used(),
                    "判定依据：转让未成功则目标配额绝不能被占用（配额不被吞）");
            assertEquals(0L, harness.ledger.teamView("teamB").used());
        } else {
            // 转让先胜出：环境归属 bob/teamB 且仍 READY；之后 alice 的终止全部 NOT_OWNER
            assertEquals(LeaseStatus.READY, finalState.status());
            assertEquals("bob", finalState.owner());
            assertEquals("teamB", finalState.team());
            assertEquals(threads / 2, transferOk.size(),
                    "判定依据：每个转让线程使用独立 requestId，转让在终止前发生则全部成功");
            assertTrue(rejects.stream().allMatch(c -> c == ErrorCode.NOT_OWNER),
                    "判定依据：易主后原归属者终止被 NOT_OWNER 拒绝，实际="
                            + rejects.stream().distinct().toList());
            assertEquals(0, terminates.get());
            assertEquals(1L, harness.ledger.ownerView("bob").used());
            assertEquals(0L, harness.ledger.ownerView("alice").used());
        }
        int expectedSlots = finalState.status() == LeaseStatus.TERMINATED ? 0 : 1;
        assertEquals(expectedSlots, harness.provisioner.getOccupiedCount(),
                "判定依据：资源槽与终态一致，不泄漏");
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
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(0L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.teamView("teamA").used());
        assertEquals(0L, harness.ledger.teamView("teamB").used(), "判定依据：配额未被转让吞掉");
        assertEquals(0, harness.provisioner.getOccupiedCount());
    }

    @Test
    @DisplayName("边界前一刻转让 vs 续租 vs 回收：结论确定，续租/转让都只认当前归属者")
    void transferVsRenewBeforeBoundary() throws Exception {
        LeaseView env = apply("race-ren", "alice", "teamA");
        harness.clock.set(Instant.parse("2026-09-22T00:09:59Z")); // 到期前 1 秒
        int threads = 24;
        ConcurrentLinkedQueue<LeaseView> transferOk = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<LeaseView> renewOk = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ErrorCode> rejects = new ConcurrentLinkedQueue<>();

        raceOneLease(threads, env.id(), idx -> {
            try {
                switch (idx % 3) {
                    case 0 -> transferOk.add(harness.service.transfer(env.id(),
                            transfer("race-ren-" + idx, "alice", "bob", "teamB")));
                    // 续租方不知道归属会变化：一半以 alice、一半以 bob 尝试，两者恰有一个在任何时刻是 owner
                    case 1 -> {
                        RenewRequest r = new RenewRequest();
                        r.setOperator(idx % 2 == 1 ? "alice" : "bob");
                        r.setExtension("PT1M");
                        renewOk.add(harness.service.renew(env.id(), r));
                    }
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

        // 未到期：回收不得翻转；最终要么已转给 bob，要么仍属 alice，但状态恒 READY 且配额守恒。
        assertEquals(LeaseStatus.READY, finalState.status());
        assertTrue(transferOk.size() <= 1,
                "判定依据：多个独立 requestId 的转让中，仅当第一次发生时有效；"
                        + "易主后其余转让以 NOT_OWNER 拒绝，实际成功=" + transferOk.size());
        assertTrue(rejects.stream().allMatch(c -> c == ErrorCode.NOT_OWNER),
                "判定依据：未到期不会有过期类拒绝；所有拒绝只能来自归属校验，实际="
                        + rejects.stream().distinct().toList());
        String owner = finalState.owner();
        assertEquals(1L, harness.ledger.ownerView(owner).used());
        assertEquals(0L, harness.ledger.ownerView(owner.equals("bob") ? "alice" : "bob").used());
        assertEquals(1, harness.provisioner.getOccupiedCount());
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
