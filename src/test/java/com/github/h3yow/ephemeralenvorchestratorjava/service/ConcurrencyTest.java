package com.github.h3yow.ephemeralenvorchestratorjava.service;

import com.github.h3yow.ephemeralenvorchestratorjava.clock.SafeClock;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.EnvironmentStatus;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.OrchestrationException;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.RejectionReason;
import com.github.h3yow.ephemeralenvorchestratorjava.provision.LocalResourceProvisioner;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发测试（重点）：
 * 1) 多线程同时申请同一配额账户：成功数必须精确等于配额，绝不超卖，且拒绝原因稳定；
 * 2) 多线程重复提交同一 requestId：只分配一个环境、一个资源。
 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    private static final long T0 = 3_000_000L;

    @Test
    void concurrentAppliesNeverOversellOwnerQuota() throws Exception {
        int quota = 5;
        int threads = 50;
        var provisioner = TestSupport.provisioner(100);
        var orch = new EnvironmentOrchestrator(
                TestSupport.props(quota, 1000, 300, 300, 1, 100),
                new SafeClock(new AtomicLong(T0)::get), provisioner);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        Set<String> successEnvIds = ConcurrentHashMap.newKeySet();
        AtomicInteger rejected = new AtomicInteger();
        Set<RejectionReason> rejectReasons = ConcurrentHashMap.newKeySet();

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS); // 同时释放，制造真实竞争
                    var r = orch.apply(new Commands.ApplyCommand("c-" + idx, "alice", null));
                    successEnvIds.add(r.envId());
                } catch (OrchestrationException e) {
                    rejected.incrementAndGet();
                    rejectReasons.add(e.getReason());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        log.info("[并发配额] 输入: {} 线程齐发, ownerQuota={}, 判定: 成功唯一环境数={}, 拒绝数={}, 拒绝原因={}, 配额used={}, 资源占用={}",
                threads, quota, successEnvIds.size(), rejected.get(), rejectReasons,
                orch.ownerQuota("alice").used(), provisioner.allocatedCount());

        assertEquals(quota, successEnvIds.size(), "成功申请数必须精确等于配额，不得超卖");
        assertEquals(threads - quota, rejected.get());
        assertEquals(Set.of(RejectionReason.OWNER_QUOTA_EXCEEDED), rejectReasons,
                "所有拒绝必须稳定归因为所有者配额超限");
        assertEquals(quota, orch.ownerQuota("alice").used());
        assertEquals(quota, provisioner.allocatedCount());
    }

    @Test
    void concurrentDuplicateSubmissionsAllocateExactlyOnce() throws Exception {
        int threads = 32;
        var provisioner = TestSupport.provisioner(100);
        var orch = new EnvironmentOrchestrator(
                TestSupport.props(100, 100, 300, 300, 1, 100),
                new SafeClock(new AtomicLong(T0)::get), provisioner);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        Set<String> envIds = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicates = new AtomicInteger();

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    var r = orch.apply(new Commands.ApplyCommand("same-request-id", "alice", null));
                    envIds.add(r.envId());
                    if (r.duplicate()) {
                        duplicates.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        log.info("[并发重复提交] 输入: {} 线程同一 requestId 齐发, 判定: 唯一envId数={}, duplicate响应数={}, 资源占用={}, 配额used={}",
                threads, envIds.size(), duplicates.get(), provisioner.allocatedCount(),
                orch.ownerQuota("alice").used());

        assertEquals(1, envIds.size(), "同一 requestId 只能得到同一个环境");
        assertEquals(threads - 1, duplicates.get(), "恰有一个首请求，其余全部幂等命中");
        assertEquals(1, provisioner.allocatedCount(), "资源只能分配一次");
        assertEquals(1, orch.ownerQuota("alice").used(), "配额只能占用一次");
    }

    @Test
    void concurrentRenewAndSweepHasConsistentOutcome() throws Exception {
        // 到期瞬间续租与扫描回收并发：要么续租成功仍 READY，要么被回收 EXPIRED；
        // 不允许“已结束仍被续租”或资源被重复释放。
        var source = new AtomicLong(T0);
        var provisioner = TestSupport.provisioner(100);
        var orch = new EnvironmentOrchestrator(
                TestSupport.props(10, 100, 100, 100, 1, 100),
                new SafeClock(source::get), provisioner);
        var r = orch.apply(new Commands.ApplyCommand("race-1", "alice", null));

        // 把时间推到恰好到期，然后续租与扫描齐发
        source.set(T0 + 100_000);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> renewFuture = pool.submit(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                orch.renew(new Commands.RenewCommand(r.envId(), "alice"));
            } catch (OrchestrationException e) {
                // 串行化后只有两种确定结论：续租路径亲自判定到期(LEASE_EXPIRED)，
                // 或扫描先完成回收导致状态非法(ILLEGAL_STATE_TRANSITION)。
                if (e.getReason() != RejectionReason.LEASE_EXPIRED
                        && e.getReason() != RejectionReason.ILLEGAL_STATE_TRANSITION) {
                    throw new RuntimeException(e);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Future<?> sweepFuture = pool.submit(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                orch.sweepExpired();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        renewFuture.get(10, TimeUnit.SECONDS);
        sweepFuture.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        var finalView = orch.get(r.envId());
        log.info("[续租回收竞态] 输入: 到期时刻 renew 与 sweep 并发, 判定: 最终状态={}, 资源占用={}, ownerUsed={}",
                finalView.status(), provisioner.allocatedCount(), orch.ownerQuota("alice").used());

        assertTrue(new HashSet<>(java.util.List.of(EnvironmentStatus.READY, EnvironmentStatus.EXPIRED))
                .contains(finalView.status()), "结论只能是续租成功或已回收，不得出现跳变");
        if (finalView.status() == EnvironmentStatus.EXPIRED) {
            assertEquals(0, provisioner.allocatedCount(), "EXPIRED 必须已释放且只释放一次");
            assertEquals(0, orch.ownerQuota("alice").used());
        } else {
            assertEquals(1, provisioner.allocatedCount(), "READY 必须持有资源");
            assertEquals(1, orch.ownerQuota("alice").used());
        }
    }
}
