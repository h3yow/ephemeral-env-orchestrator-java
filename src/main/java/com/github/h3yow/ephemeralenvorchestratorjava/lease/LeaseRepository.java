package com.github.h3yow.ephemeralenvorchestratorjava.lease;

import com.github.h3yow.ephemeralenvorchestratorjava.domain.Lease;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.TransferReceipt;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地内存租约仓储（模拟持久化）。
 *
 * <p>leaseId 主索引、requestId 申请幂等索引、transferRequestId 转让幂等索引分开维护。
 * {@link #registerRequest} 使用 putIfAbsent 原子占位：同一 requestId 的并发申请只有一个线程
 * 能登记成功；转让索引同理，保证同一次转让重复提交（含并发）只生效一次。</p>
 */
@Component
public class LeaseRepository {

    private final ConcurrentHashMap<String, Lease> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> requestIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TransferReceipt> transferIndex = new ConcurrentHashMap<>();

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

    // -------------------------------------------------------- 转让幂等索引

    /**
     * 查找转让回执：存在时返回此前成功转让的凭据（用于幂等回放或冲突识别）。
     */
    public TransferReceipt findTransfer(String transferRequestId) {
        return transferIndex.get(transferRequestId);
    }

    /**
     * 登记转让回执。仅在编排服务的全局锁内调用：调用前已判定该键不存在，
     * 因而同一次转让（含两端几乎同时提交）至多登记一次、至多生效一次。
     */
    public void saveTransfer(TransferReceipt receipt) {
        transferIndex.put(receipt.transferRequestId(), receipt);
    }

    /** 判断某申请幂等键是否已占用（申请/转让幂等键命名空间冲突检测）。 */
    public boolean existsRequest(String requestId) {
        return requestIndex.containsKey(requestId);
    }

    /** 判断某转让幂等键是否已占用（申请/转让幂等键命名空间冲突检测）。 */
    public boolean existsTransfer(String transferRequestId) {
        return transferIndex.containsKey(transferRequestId);
    }
}
