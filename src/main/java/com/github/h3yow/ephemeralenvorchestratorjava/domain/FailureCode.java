package com.github.h3yow.ephemeralenvorchestratorjava.domain;

/**
 * 供给失败的可解释原因码。失败记录会保留每次尝试的原因，便于审计与重试判定。
 */
public enum FailureCode {
    RESOURCE_POOL_EXHAUSTED,
    PROVISIONER_REJECTED,
    PROVISIONER_TRANSIENT,
    PROVISIONER_ERROR,
    RETRY_LIMIT_EXCEEDED
}
