package com.github.h3yow.ephemeralenvorchestratorjava.domain;

import java.time.Instant;

/**
 * 一次供给失败的不可变记录。失败历史必须被保留并可解释，
 * 达到重试上限后据此判定 {@link RejectionReason#RETRY_LIMIT_EXHAUSTED}。
 *
 * @param attempt     第几次尝试（从 1 开始）
 * @param occurredAt  失败发生时刻（单调安全时钟）
 * @param reasonCode  失败原因码（如 RESOURCE_SHORTAGE / PROVISIONER_ERROR）
 * @param detail      可读细节
 */
public record FailureRecord(int attempt, Instant occurredAt, String reasonCode, String detail) {
}
