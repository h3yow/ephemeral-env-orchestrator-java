package com.github.h3yow.ephemeralenvorchestratorjava.domain;

/**
 * 临时环境生命周期状态。
 *
 * <pre>
 * PENDING -> PROVISIONING -> READY -> TERMINATED
 *                    |          |
 *                    v          v
 *                 FAILED    EXPIRED
 *
 * PENDING 也可能因首次供给失败直接进入 FAILED（PENDING -> PROVISIONING -> FAILED）。
 * FAILED 可在重试上限内回到 PROVISIONING；TERMINATED/EXPIRED/FAILED(超限) 为终态。
 * </pre>
 */
public enum LeaseStatus {
    PENDING,
    PROVISIONING,
    READY,
    FAILED,
    TERMINATED,
    EXPIRED
}
