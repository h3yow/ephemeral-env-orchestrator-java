package com.github.h3yow.ephemeralenvorchestratorjava.lease;

import com.github.h3yow.ephemeralenvorchestratorjava.domain.Lease;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地内存租约仓储（模拟持久化）。
 *
 * <p>leaseId 主索引与 requestId 幂等索引分开维护。{@link #registerRequest} 使用
 * putIfAbsent 原子占位：同一 requestId 的并发申请只有一个线程能登记成功，
 * 其余线程拿到已登记的 leaseId，从源头保证不会创建第二个租约、不会重复分配资源。</p>
 */
@Component
public class LeaseRepository {

    private final ConcurrentHashMap<String, Lease> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> requestIndex = new ConcurrentHashMap<>();

    /**
     * 幂等占位。
     *
     * @return 若 requestId 已登记，返回已存在的 leaseId（调用方据此复用）；否则登记成功返回 null
     */
    public String registerRequest(String requestId, String leaseId) {
        return requestIndex.putIfAbsent(requestId, leaseId);
    }

    public void save(Lease lease) {
        byId.put(lease.getId(), lease);
    }

    public Optional<Lease> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<Lease> findByRequestId(String requestId) {
        String id = requestIndex.get(requestId);
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public Collection<Lease> findAll() {
        return Collections.unmodifiableCollection(byId.values());
    }
}
