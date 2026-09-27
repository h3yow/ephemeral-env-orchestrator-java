package com.github.h3yow.ephemeralenvorchestratorjava.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 临时环境租约聚合。自身不做并发保护：所有状态变更必须由编排服务持锁后串行调用。
 *
 * <p>合法流转：
 * <pre>
 *   PENDING -> PROVISIONING -> READY -> TERMINATED
 *                              |  READY -> EXPIRED
 *                              +-> FAILED -> PROVISIONING（重试，未达上限）
 *                              +-> FAILED -> TERMINATED
 * </pre>
 * 终态：TERMINATED、EXPIRED、以及达到尝试上限后的 FAILED。
 */
public class Lease {

    private final String id;
    private final String requestId;
    private String owner;
    private String team;
    private final Instant createdAt;
    private final Duration ttl;
    private final int maxAttempts;

    private LeaseStatus status;
    private Instant lastStatusChangedAt;
    private Instant expiresAt;
    private String endpoint;
    private String resourceSlot;
    private int attemptCount;
    private final List<FailureRecord> failures = new ArrayList<>();
    /** 配额是否仍被占用；releaseQuota 幂等，保证配额不会被重复释放。 */
    private boolean quotaHeld;

    public Lease(String id, String requestId, String owner, String team,
                 Instant now, Duration ttl, int maxAttempts) {
        this.id = id;
        this.requestId = requestId;
        this.owner = owner;
        this.team = team;
        this.createdAt = now;
        this.ttl = ttl;
        this.maxAttempts = maxAttempts;
        this.status = LeaseStatus.PENDING;
        this.lastStatusChangedAt = now;
    }

    public String getId() { return id; }
    public String getRequestId() { return requestId; }
    public String getOwner() { return owner; }
    public String getTeam() { return team; }
    public Instant getCreatedAt() { return createdAt; }
    public Duration getTtl() { return ttl; }
    public int getMaxAttempts() { return maxAttempts; }
    public LeaseStatus getStatus() { return status; }
    public Instant getLastStatusChangedAt() { return lastStatusChangedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getEndpoint() { return endpoint; }
    public String getResourceSlot() { return resourceSlot; }
    public int getAttemptCount() { return attemptCount; }
    public List<FailureRecord> getFailures() { return Collections.unmodifiableList(failures); }
    public boolean isQuotaHeld() { return quotaHeld; }

    /** PENDING 或（重试时的）FAILED -> PROVISIONING。 */
    public void markProvisioning(Instant at) {
        if (status != LeaseStatus.PENDING && status != LeaseStatus.FAILED) {
            throw new IllegalStateException("仅 PENDING/FAILED 可进入 PROVISIONING，当前=" + status);
        }
        if (status == LeaseStatus.FAILED && !canRetry()) {
            throw new IllegalStateException("已达到供给尝试上限，不可再重试");
        }
        status = LeaseStatus.PROVISIONING;
        lastStatusChangedAt = at;
    }

    /** 记录一次失败尝试并切到 FAILED，返回被保留的失败记录。 */
    public FailureRecord recordAttempt(FailureCode code, String message, Instant at) {
        attemptCount++;
        FailureRecord record = new FailureRecord(attemptCount, code, message, at);
        failures.add(record);
        status = LeaseStatus.FAILED;
        lastStatusChangedAt = at;
        return record;
    }

    /** PROVISIONING -> READY，记录访问入口、资源槽与到期时刻。 */
    public void markReady(String endpoint, String resourceSlot, Instant expiresAt, Instant at) {
        if (status != LeaseStatus.PROVISIONING) {
            throw new IllegalStateException("仅 PROVISIONING 可进入 READY，当前=" + status);
        }
        this.endpoint = endpoint;
        this.resourceSlot = resourceSlot;
        this.expiresAt = expiresAt;
        this.status = LeaseStatus.READY;
        this.lastStatusChangedAt = at;
    }

    /** 是否还能再尝试供给（失败记录数小于上限）。 */
    public boolean canRetry() {
        return attemptCount < maxAttempts;
    }

    /** 主动终止：非终态（含可重试/超限 FAILED）均可终止；返回 true 表示本次发生了状态变更。 */
    public boolean markTerminated(Instant at) {
        if (status == LeaseStatus.TERMINATED) {
            return false;
        }
        if (status == LeaseStatus.EXPIRED) {
            throw new IllegalStateException("租约已过期回收，不能终止为 TERMINATED");
        }
        status = LeaseStatus.TERMINATED;
        lastStatusChangedAt = at;
        return true;
    }

    /** 到期回收：仅 READY 可转 EXPIRED；返回 true 表示本次发生了状态变更。 */
    public boolean markExpired(Instant at) {
        if (status == LeaseStatus.EXPIRED) {
            return false;
        }
        if (status != LeaseStatus.READY) {
            throw new IllegalStateException("仅 READY 租约可过期回收，当前=" + status);
        }
        status = LeaseStatus.EXPIRED;
        lastStatusChangedAt = at;
        return true;
    }

    /**
     * 续租：仅 READY 租约可续租。以当前到期时刻为基准延长（当前时间为准，
     * 时钟回拨场景由编排服务在调用前拒绝），保证不会延长已结束的租约。
     * 返回新的到期时刻；若租约在 now 已过期则返回 null（调用方应执行回收并拒绝续租）。
     */
    public Instant extend(Instant now, Duration extension) {
        if (status != LeaseStatus.READY) {
            throw new IllegalStateException("仅 READY 租约可续租，当前=" + status);
        }
        if (expiresAt == null || !now.isBefore(expiresAt)) {
            return null;
        }
        expiresAt = expiresAt.plus(extension);
        lastStatusChangedAt = now;
        return expiresAt;
    }

    public boolean isExpiredAt(Instant now) {
        return status == LeaseStatus.READY && expiresAt != null && !now.isBefore(expiresAt);
    }

    /**
     * 原地转让：仅 READY 且未到期的租约可转让。环境不重新供给、不换实例，
     * endpoint / resourceSlot / expiresAt / ttl 全部保持不变；配额占用标志也保持占用，
     * 配额的“源释放 + 目标占用”由编排服务在同一临界区内按维度原子搬运。
     *
     * <p>仅 owner 或 team 至少有一个发生变化时才应调用（完全相同的归属由服务层提前拒绝）。</p>
     */
    public void transferOwnership(String newOwner, String newTeam, Instant at) {
        if (status != LeaseStatus.READY) {
            throw new IllegalStateException("仅 READY 租约可转让，当前=" + status);
        }
        if (expiresAt == null || !at.isBefore(expiresAt)) {
            throw new IllegalStateException("仅未到期租约可转让，at=" + at + " expiresAt=" + expiresAt);
        }
        this.owner = newOwner;
        this.team = newTeam;
        this.lastStatusChangedAt = at;
    }

    /** 占用配额（申请受理时调用）。 */
    public void acquireQuota() {
        quotaHeld = true;
    }

    /**
     * 释放配额，幂等：仅第一次调用返回 true。
     * 终止、过期、达到重试上限的失败三条路径都可能触发，重复释放被严格防止。
     */
    public boolean releaseQuota() {
        if (quotaHeld) {
            quotaHeld = false;
            return true;
        }
        return false;
    }
}
