package com.github.h3yow.ephemeralenvorchestratorjava.error;

/**
 * 业务拒绝原因码。三类语义必须相互可区分：
 * <ul>
 *   <li>状态非法：{@link #LEASE_ALREADY_TERMINATED} 等；</li>
 *   <li>配额不足：{@link #OWNER_QUOTA_EXCEEDED} / {@link #TEAM_QUOTA_EXCEEDED}；</li>
 *   <li>资源不足：{@link #RESOURCE_INSUFFICIENT}。</li>
 * </ul>
 */
public enum ErrorCode {
    VALIDATION_ERROR,
    LEASE_NOT_FOUND,
    DUPLICATE_REQUEST_IN_FLIGHT,
    INVALID_STATE_TRANSITION,
    LEASE_NOT_READY,
    LEASE_FAILED,
    LEASE_ALREADY_TERMINATED,
    LEASE_ALREADY_EXPIRED,
    RETRY_NOT_ALLOWED,
    RETRY_LIMIT_EXCEEDED,
    NOT_OWNER,
    OWNER_QUOTA_EXCEEDED,
    TEAM_QUOTA_EXCEEDED,
    RESOURCE_INSUFFICIENT,
    /** 转让时环境当前状态不允许转让（非 READY 且非终态，如供给中/失败中）。 */
    TRANSFER_NOT_ALLOWED,
    /** 转让幂等键冲突：同一 transferRequestId 此前已用于另一条环境或另一个目标归属。 */
    TRANSFER_REQUEST_CONFLICT,
    CLOCK_ROLLBACK_DETECTED
}
