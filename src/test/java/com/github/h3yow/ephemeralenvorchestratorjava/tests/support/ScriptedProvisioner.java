package com.github.h3yow.ephemeralenvorchestratorjava.tests.support;

import com.github.h3yow.ephemeralenvorchestratorjava.domain.Lease;
import com.github.h3yow.ephemeralenvorchestratorjava.provision.ProvisionResult;
import com.github.h3yow.ephemeralenvorchestratorjava.provision.Provisioner;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可控供给器：
 * <ul>
 *   <li>可设置前若干次供给失败/资源耗尽/抛异常，用于验证重试与“资源不足”原因区分；</li>
 *   <li>可在供给入口阻塞（栅栏），制造“续租/终止 与 供给完成”并发窗口；</li>
 *   <li>统计实际供给成功次数，断言“重复提交不得重复分配资源”。</li>
 * </ul>
 */
public class ScriptedProvisioner implements Provisioner {

    public enum Mode { OK, EXHAUSTED, FAIL, THROW }

    private final ConcurrentHashMap<String, String> occupied = new ConcurrentHashMap<>();
    private final AtomicInteger slotSeq = new AtomicInteger();
    private final AtomicInteger successCount = new AtomicInteger();
    private final AtomicInteger callCount = new AtomicInteger();

    private volatile Mode mode = Mode.OK;
    /** 前 failFirstCalls 次调用按 mode 处理，之后恢复 OK。 */
    private volatile int failFirstCalls = 0;
    private volatile CountDownLatch enterLatch;
    private volatile CountDownLatch releaseLatch;

    public void setScript(Mode mode, int failFirstCalls) {
        this.mode = mode;
        this.failFirstCalls = failFirstCalls;
    }

    /** 在每次调用进入时 countDown enterLatch，并等待 releaseLatch 放行。 */
    public void armBlock(CountDownLatch enterLatch, CountDownLatch releaseLatch) {
        this.enterLatch = enterLatch;
        this.releaseLatch = releaseLatch;
    }

    public void clearBlock() {
        this.enterLatch = null;
        this.releaseLatch = null;
    }

    public int getSuccessCount() {
        return successCount.get();
    }

    public int getCallCount() {
        return callCount.get();
    }

    public int getOccupiedCount() {
        return occupied.size();
    }

    @Override
    public ProvisionResult provision(Lease lease, int attempt) {
        int n = callCount.incrementAndGet();
        CountDownLatch enter = enterLatch;
        CountDownLatch release = releaseLatch;
        if (ready(enter, release)) {
            enter.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (n <= failFirstCalls) {
            return switch (mode) {
                case EXHAUSTED -> ProvisionResult.exhausted("脚本注入：资源池耗尽 call=" + n);
                case FAIL -> ProvisionResult.failure("脚本注入：供给瞬时失败 call=" + n);
                case THROW -> throw new IllegalStateException("脚本注入：供给器异常 call=" + n);
                default -> allocate();
            };
        }
        return allocate();
    }

    private static boolean ready(CountDownLatch enter, CountDownLatch release) {
        return enter != null && release != null;
    }

    private ProvisionResult allocate() {
        String slot = "slot-" + slotSeq.incrementAndGet();
        occupied.put(slot, slot);
        successCount.incrementAndGet();
        return ProvisionResult.ok("http://" + slot + ".local", slot);
    }

    @Override
    public void release(String resourceSlot) {
        if (resourceSlot != null) {
            occupied.remove(resourceSlot);
        }
    }
}
