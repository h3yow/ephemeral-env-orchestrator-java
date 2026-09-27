package com.github.h3yow.ephemeralenvorchestratorjava.domain;

import java.time.Instant;

/**
 * 转让幂等回执：一次转让成功后留存的凭据，用于同 transferRequestId 的重复提交回放同一结论。
 *
 * <p>为了让“转让成功之后环境又被新主人终止/过期”不影响重复提交拿到的一致结论，
 * 回执中保存转让生效那一刻的租约不可变快照，而非引用可变租约对象。
 */
public record TransferReceipt(
        String transferRequestId,
        String leaseId,
        String fromOwner,
        String fromTeam,
        String toOwner,
        String toTeam,
        Instant transferredAt,
        Lease snapshot
) {
}
