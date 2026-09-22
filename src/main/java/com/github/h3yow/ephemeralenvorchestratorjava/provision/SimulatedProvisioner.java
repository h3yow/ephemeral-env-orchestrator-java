package com.github.h3yow.ephemeralenvorchestratorjava.provision;

import com.github.h3yow.ephemeralenvorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.Lease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 默认模拟供给器：维护一个容量有限的资源槽池（集合表示占用中的槽位）。
 *
 * <p>语义：
 * <ul>
 *   <li>池中有空槽：占用一个并返回成功（endpoint + resourceSlot）；</li>
 *   <li>池满：返回 resourceExhausted=true（对应 RESOURCE_INSUFFICIENT，可重试）；</li>
 *   <li>{@link #release(String)} 回收槽位，幂等。</li>
 * </ul>
 * 供给与回收都在集合自身的锁上原子完成。容量由 orchestrator.resource-pool-capacity 配置。</p>
 */
@Component
public class SimulatedProvisioner implements Provisioner {

    private static final Logger log = LoggerFactory.getLogger(SimulatedProvisioner.class);

    private final int capacity;
    private final AtomicLong slotSeq = new AtomicLong();
    private final Set<String> occupied = Collections.synchronizedSet(new HashSet<>());

    public SimulatedProvisioner(OrchestratorProperties properties) {
        this.capacity = properties.getResourcePoolCapacity();
    }

    @Override
    public ProvisionResult provision(Lease lease, int attempt) {
        String slot;
        synchronized (occupied) {
            if (occupied.size() >= capacity) {
                log.warn("供给判定：leaseId={} attempt={} 资源池已满 capacity={} -> 资源不足",
                        lease.getId(), attempt, capacity);
                return ProvisionResult.exhausted(
                        "资源池容量不足，capacity=" + capacity + "，当前全部占用");
            }
            slot = "slot-" + slotSeq.incrementAndGet();
            occupied.add(slot);
        }
        String endpoint = "http://" + slot + ".env-" + lease.getId() + ".local:8080";
        log.info("供给判定：leaseId={} attempt={} 分配 {} -> 成功", lease.getId(), attempt, slot);
        return ProvisionResult.ok(endpoint, slot);
    }

    @Override
    public void release(String resourceSlot) {
        if (resourceSlot == null) {
            return;
        }
        boolean removed = occupied.remove(resourceSlot);
        log.info("供给资源回收：slot={} removed={}", resourceSlot, removed);
    }

    /** 测试/诊断用：当前占用槽位数。 */
    public int occupiedCount() {
        return occupied.size();
    }
}
