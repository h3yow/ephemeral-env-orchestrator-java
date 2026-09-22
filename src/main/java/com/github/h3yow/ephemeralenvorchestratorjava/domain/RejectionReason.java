package com.github.h3yow.ephemeralenvorchestratorjava.domain;

/**
 * 操作被拒绝的可区分原因。不同入口（HTTP 状态码、返回体 reason 字段）必须透出该枚举，
 * 使“状态非法 / 归属不符 / 配额超限 / 资源不足 / 租约过期 / 幂等冲突”等结论互不混淆。
 */
public enum RejectionReason {
    /** 请求参数不合法（空值、负数等）。 */
    INVALID_REQUEST,
    /** 配置项非法（启动期直接失败，不会走到这里；保留用于防御）。 */
    INVALID_CONFIG,
    /** 幂等键不存在或不属于当前操作上下文。 */
    IDEMPOTENCY_KEY_NOT_FOUND,
    /** 环境 ID 不存在。 */
    ENV_NOT_FOUND,
    /** 同一幂等键被用于不同语义的请求体。 */
    IDEMPOTENCY_CONFLICT,
    /** 当前状态不允许执行该操作（非法状态流转）。 */
    ILLEGAL_STATE_TRANSITION,
    /** 操作者不是环境所有者（跨所有者操作被拒绝）。 */
    NOT_OWNER,
    /** 所有者配额已满。 */
    OWNER_QUOTA_EXCEEDED,
    /** 团队配额已满。 */
    TEAM_QUOTA_EXCEEDED,
    /** 资源池容量不足。 */
    INSUFFICIENT_RESOURCES,
    /** 供给器执行失败（可重试，失败记录会被保留）。 */
    PROVISION_FAILED,
    /** 已达到供给重试上限，不得再重试，也不得静默视为成功。 */
    RETRY_LIMIT_EXHAUSTED,
    /** 租约已过期（续租时触发并执行回收）。 */
    LEASE_EXPIRED
}
