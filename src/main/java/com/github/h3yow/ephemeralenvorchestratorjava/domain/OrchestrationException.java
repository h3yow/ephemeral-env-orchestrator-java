package com.github.h3yow.ephemeralenvorchestratorjava.domain;

/**
 * 编排层业务拒绝异常。所有“可解释的拒绝”都以该异常抛出，
 * 携带稳定的 {@link RejectionReason}，由 Web 层映射为确定的 HTTP 状态码。
 */
public class OrchestrationException extends RuntimeException {

    private final RejectionReason reason;

    public OrchestrationException(RejectionReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectionReason getReason() {
        return reason;
    }
}
