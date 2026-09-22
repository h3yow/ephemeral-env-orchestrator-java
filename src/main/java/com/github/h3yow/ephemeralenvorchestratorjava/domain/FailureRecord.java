package com.github.h3yow.ephemeralenvorchestratorjava.domain;

import java.time.Instant;

/**
 * 一次供给尝试的失败记录（不可变）。
 */
public record FailureRecord(
        int attemptNumber,
        FailureCode code,
        String message,
        Instant occurredAt
) {
}
