package com.github.h3yow.ephemeralenvorchestratorjava.web.dto;

import com.github.h3yow.ephemeralenvorchestratorjava.domain.FailureRecord;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.Lease;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.LeaseStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 租约只读视图，包含最新状态、到期时间与失败历史（失败记录必须保留可解释）。
 */
public record LeaseView(
        String id,
        String requestId,
        String owner,
        String team,
        LeaseStatus status,
        Instant createdAt,
        Instant lastStatusChangedAt,
        Instant expiresAt,
        String endpoint,
        int attemptCount,
        int maxAttempts,
        Duration ttl,
        List<FailureRecord> failures,
        boolean quotaHeld
) {
    public static LeaseView from(Lease lease) {
        return new LeaseView(
                lease.getId(),
                lease.getRequestId(),
                lease.getOwner(),
                lease.getTeam(),
                lease.getStatus(),
                lease.getCreatedAt(),
                lease.getLastStatusChangedAt(),
                lease.getExpiresAt(),
                lease.getEndpoint(),
                lease.getAttemptCount(),
                lease.getMaxAttempts(),
                lease.getTtl(),
                lease.getFailures(),
                lease.isQuotaHeld()
        );
    }
}
