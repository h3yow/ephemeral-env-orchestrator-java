package com.github.h3yow.ephemeralenvorchestratorjava.provision;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

/**
 * 本地资源供给器：用原子计数器模拟固定容量资源池。
 *
 * <p>容量取满时返回 {@link ProvisionOutcome.Failure}（原因 RESOURCE_SHORTAGE），
 * 不抛异常，使“资源不足”与其他失败均可解释、可重试；{@link #release(String)} 幂等，
 * 重复回收同一资源不会重复释放配额。</p>
 *
 * <p>可通过失败注入钩子按 (envId, attempt) 确定性地制造失败（如前两次失败第三次成功），
 * 默认钩子恒为成功。注意：容量不足优先于注入钩子，保证“资源不足”结论优先成立。</p>
 */
public class LocalResourceProvisioner implements ResourceProvisioner {

    public static final String REASON_RESOURCE_SHORTAGE = "RESOURCE_SHORTAGE";
    public static final String REASON_INJECTED_FAILURE = "PROVISIONER_ERROR";

    private final int poolSize;
    private final AtomicInteger allocated = new AtomicInteger(0);
    private final Set<String> liveResources = ConcurrentHashMap.newKeySet();
    private final AtomicInteger resourceSeq = new AtomicInteger(0);

    private volatile BiPredicate<String, Integer> failureInjection = (envId, attempt) -> false;

    public LocalResourceProvisioner(int poolSize) {
        if (poolSize <= 0) {
            throw new IllegalArgumentException("poolSize 必须为正整数: " + poolSize);
        }
        this.poolSize = poolSize;
    }

    /** 注入失败判定钩子（测试用，返回 true 的那次尝试记为失败且不占资源）。 */
    public void setFailureInjection(BiPredicate<String, Integer> failureInjection) {
        this.failureInjection = failureInjection == null ? (e, a) -> false : failureInjection;
    }

    @Override
    public ProvisionOutcome provision(String envId, int attempt) {
        // CAS 式占位：保证并发申请不超卖资源池
        int current;
        int next;
        do {
            current = allocated.get();
            if (current >= poolSize) {
                return new ProvisionOutcome.Failure(REASON_RESOURCE_SHORTAGE,
                        "资源池已满，容量=" + poolSize + "，已分配=" + current);
            }
            next = current + 1;
        } while (!allocated.compareAndSet(current, next));

        // 已占位后注入失败：必须立即归还占位，避免失败长期占用资源
        if (failureInjection.test(envId, attempt)) {
            allocated.decrementAndGet();
            return new ProvisionOutcome.Failure(REASON_INJECTED_FAILURE,
                    "第 " + attempt + " 次供给被注入失败");
        }

        String resourceId = "res-" + resourceSeq.incrementAndGet();
        liveResources.add(resourceId);
        return new ProvisionOutcome.Success(resourceId);
    }

    @Override
    public void release(String resourceId) {
        if (resourceId == null) {
            return;
        }
        // 仅当资源仍在存活集合中时才递减计数：回收/终止与其他路径并发时不会重复释放
        if (liveResources.remove(resourceId)) {
            allocated.decrementAndGet();
        }
    }

    /** 当前已分配数量（观测/测试用）。 */
    public int allocatedCount() {
        return allocated.get();
    }

    /** 资源池容量（观测/测试用）。 */
    public int poolSize() {
        return poolSize;
    }
}
