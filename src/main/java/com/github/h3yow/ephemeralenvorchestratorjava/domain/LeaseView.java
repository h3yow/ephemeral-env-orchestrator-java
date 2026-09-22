package com.github.h3yow.ephemeralenvorchestratorjava.domain;

import java.time.Instant;
import java.util.List;

/**
 * 环境租约的不可变快照（对各入口透出的统一视图）。
 *
 * @param envId           环境 ID
 * @param requestId       申请幂等键
 * @param owner           所有者
 * @param team            所属团队
 * @param status          当前状态
 * @param createdAt       创建时刻
 * @param readyAt         就绪时刻（未就绪为 null）
 * @param expiresAt       租约到期时刻（仅 READY 有意义）
 * @param renewedCount    已续租次数
 * @param resourceId      已分配资源 ID（未分配为 null）
 * @param failures        供给失败记录（永不丢失）
 * @param terminalMessage 终态说明（到期回收/主动终止/重试耗尽原因）
 */
public record LeaseView(
        String envId,
        String requestId,
        String owner,
        String team,
        EnvironmentStatus status,
        Instant createdAt,
        Instant readyAt,
        Instant expiresAt,
        int renewedCount,
        String resourceId,
        List<FailureRecord> failures,
        String terminalMessage
) {
}
