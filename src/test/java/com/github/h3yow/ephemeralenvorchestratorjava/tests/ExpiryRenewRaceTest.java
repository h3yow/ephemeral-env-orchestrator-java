package com.github.h3yow.ephemeralenvorchestratorjava.tests;

import com.github.h3yow.ephemeralenvorchestratorjava.domain.LeaseStatus;
import com.github.h3yow.ephemeralenvorchestratorjava.error.BusinessException;
import com.github.h3yow.ephemeralenvorchestratorjava.error.ErrorCode;
import com.github.h3yow.ephemeralenvorchestratorjava.tests.support.TestHarness;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.ApplyLeaseRequest;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.LeaseView;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.RenewRequest;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 续租与过期回收竞态、时钟回拨安全测试。
 */
class ExpiryRenewRaceTest {

    private static final Logger log = LoggerFactory.getLogger(ExpiryRenewRaceTest.class);

    private TestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new TestHarness(5L, 100L);
    }

    private LeaseView create(String requestId, String owner) {
        ApplyLeaseRequest r = new ApplyLeaseRequest();
        r.setRequestId(requestId);
        r.setOwner(owner);
        r.setTeam("teamA");
        r.setTtl("PT10M");
        return harness.service.apply(r);
    }

    private RenewRequest renew(String operator, String ext) {
        RenewRequest r = new RenewRequest();
        r.setOperator(operator);
        r.setExtension(ext);
        return r;
    }

    @Test
    @DisplayName("到期边界并发：续租与回收同时发生，结论确定——原子回收且所有续租以 LEASE_ALREADY_EXPIRED 拒绝")
    void renewVsReapAtBoundary() throws Exception {
        LeaseView lease = create("race-exp", "alice");
        // 将时钟推到到期时刻：00:10:00，isExpiredAt(now >= expiresAt) 为 true
        harness.clock.set(Instant.parse("2026-09-22T00:10:00Z"));

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<LeaseStatus> renewOk = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ErrorCode> renewReject = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Integer> reapCounts = new ConcurrentLinkedQueue<>();

        log.info("===== 输入：now=00:10:00=expiresAt，{} 线程并发：一半续租一半回收 leaseId={} =====",
                threads, lease.id());
        for (int i = 0; i < threads; i++) {
            final boolean doRenew = i % 2 == 0;
            pool.submit(() -> {
                try {
                    start.await();
                    if (doRenew) {
                        try {
                            LeaseView v = harness.service.renew(lease.id(), renew("alice", "PT5M"));
                            renewOk.add(v.status());
                        } catch (BusinessException ex) {
                            renewReject.add(ex.getCode());
                        }
                    } else {
                        reapCounts.add(harness.service.reapExpired());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        LeaseView finalState = harness.service.get(lease.id());
        long totalReaped = reapCounts.stream().mapToInt(Integer::intValue).sum();
        // 翻转 READY->EXPIRED 可能由 reap 线程或续租线程内部的原子回收完成：
        // 若由续租线程先翻转，则所有 reap 都为 0（totalReaped=0）；
        // 若由某个 reap 先翻转，则 totalReaped=1，其余 reap 为 0。两种形态都合法。
        log.info("判定结果：终态={} 续租成功次数={} 续租拒绝次数={}(codes={}) reap翻转次数={}",
                finalState.status(), renewOk.size(), renewReject.size(),
                renewReject.stream().distinct().toList(), totalReaped);

        // 确定性结论：边界上不允许任何一次续租成功；终态必须 EXPIRED；
        // READY->EXPIRED 全局至多翻转一次（无论由哪个入口完成）。
        assertTrue(renewOk.isEmpty(), "判定依据：已到期环境绝不能被续租");
        assertEquals(LeaseStatus.EXPIRED, finalState.status());
        assertTrue(totalReaped <= 1, "判定依据：状态翻转只能发生一次，实际 reap 翻转=" + totalReaped);
        assertTrue(renewReject.stream().allMatch(c -> c == ErrorCode.LEASE_ALREADY_EXPIRED),
                "判定依据：全部续租以明确的 LEASE_ALREADY_EXPIRED 拒绝，实际="
                        + renewReject.stream().distinct().toList());
        assertFalse(finalState.quotaHeld(), "判定依据：过期后配额已释放");
        assertEquals(0L, harness.ledger.ownerView("alice").used(),
                "判定依据：配额只释放一次，账本归零，无半更新状态");
        assertEquals(0, harness.provisioner.getOccupiedCount(),
                "判定依据：供给资源槽也已回收（不区分由续租还是回收入口触发）");
    }

    @Test
    @DisplayName("边界前并发：到期前 1 秒续租与回收并发，续租确定成功且回收不得翻转状态")
    void renewWinsBeforeBoundary() throws Exception {
        LeaseView lease = create("race-before", "alice");
        harness.clock.set(Instant.parse("2026-09-22T00:09:59Z"));

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<LeaseStatus> renewOk = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ErrorCode> renewReject = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Integer> reapCounts = new ConcurrentLinkedQueue<>();

        log.info("===== 输入：now=00:09:59（到期前 1 秒），{} 线程并发续租/回收 leaseId={} =====",
                threads, lease.id());
        for (int i = 0; i < threads; i++) {
            final boolean doRenew = i % 2 == 0;
            pool.submit(() -> {
                try {
                    start.await();
                    if (doRenew) {
                        try {
                            renewOk.add(harness.service.renew(lease.id(), renew("alice", "PT5M")).status());
                        } catch (BusinessException ex) {
                            renewReject.add(ex.getCode());
                        }
                    } else {
                        reapCounts.add(harness.service.reapExpired());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        LeaseView finalState = harness.service.get(lease.id());
        log.info("判定结果：终态={} 续租成功={} 续租拒绝={} 回收次数={} expiresAt={}",
                finalState.status(), renewOk.size(), renewReject.size(),
                reapCounts.stream().mapToInt(Integer::intValue).sum(), finalState.expiresAt());

        assertEquals(LeaseStatus.READY, finalState.status(), "判定依据：未到期不得回收");
        assertEquals(threads / 2, renewOk.size(), "判定依据：到期前所有续租成功");
        assertTrue(renewReject.isEmpty());
        assertEquals(Instant.parse("2026-09-22T01:10:00Z"), finalState.expiresAt(),
                "判定依据：12 次续租都在当前到期时间上累加 5 分钟（00:10 + 12*5m = 01:10）");
        assertEquals(1L, harness.ledger.ownerView("alice").used());
    }

    @Test
    @DisplayName("未到期时回收扫描不动租约；到期后查询惰性回收，续租边界前一秒仍可成功")
    void expiryBoundarySemantics() {
        LeaseView lease = create("exp-2", "alice");

        harness.clock.advance(Duration.ofMinutes(9).plusSeconds(59));
        assertEquals(0, harness.service.reapExpired(), "判定依据：now < expiresAt 不得提前回收");
        LeaseView renewed = harness.service.renew(lease.id(), renew("alice", null));
        assertEquals(Instant.parse("2026-09-22T00:15:00Z"), renewed.expiresAt(),
                "判定依据：默认续租 5 分钟，基于原到期时间延长");
        log.info("到期前 1 秒续租成功 expiresAt={}", renewed.expiresAt());

        harness.clock.set(Instant.parse("2026-09-22T00:15:00Z"));
        LeaseView viewed = harness.service.get(lease.id());
        assertEquals(LeaseStatus.EXPIRED, viewed.status(), "判定依据：查询触发惰性回收");
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        log.info("到点查询 -> 惰性回收 EXPIRED，配额已释放");
    }

    @Test
    @DisplayName("时钟回拨：时间敏感写操作一律拒绝，不提前回收有效租约、不延长已结束租约")
    void clockRollbackRejected() {
        LeaseView lease = create("exp-3", "alice");
        harness.clock.set(Instant.parse("2026-09-22T00:05:00Z"));
        // 一次正常续租推进高水位到 00:05
        harness.service.renew(lease.id(), renew("alice", "PT5M"));

        // 回拨到 00:01
        harness.clock.set(Instant.parse("2026-09-22T00:01:00Z"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> harness.service.renew(lease.id(), renew("alice", "PT5M")));
        assertEquals(ErrorCode.CLOCK_ROLLBACK_DETECTED, ex.getCode());
        log.info("回拨后续租被拒绝 code={} message={}", ex.getCode(), ex.getMessage());

        // 回拨时间下的回收扫描必须跳过：有效租约不被提前回收
        assertEquals(0, harness.service.reapExpired(), "判定依据：回拨时回收跳过，租约仍 READY");
        LeaseView unchanged = harness.service.get(lease.id());
        assertEquals(LeaseStatus.READY, unchanged.status(), "判定依据：回拨不得提前回收有效租约");
        assertEquals(Instant.parse("2026-09-22T00:15:00Z"), unchanged.expiresAt(),
                "判定依据：被拒绝的续租不得改动到期时间");
        assertEquals(1L, harness.ledger.ownerView("alice").used());

        // 时钟恢复并超过高水位后恢复正常，且越过真实到期时间可正常回收
        harness.clock.set(Instant.parse("2026-09-22T00:15:01Z"));
        assertEquals(1, harness.service.reapExpired());
        assertEquals(LeaseStatus.EXPIRED, harness.service.get(lease.id()).status());
        log.info("时钟恢复到高水位之后，正常过期回收生效");
    }
}
