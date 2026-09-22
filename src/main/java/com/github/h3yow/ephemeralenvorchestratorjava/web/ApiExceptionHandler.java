package com.github.h3yow.ephemeralenvorchestratorjava.web;

import com.github.h3yow.ephemeralenvorchestratorjava.domain.OrchestrationException;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.RejectionReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * 将可解释的业务拒绝映射为确定的 HTTP 状态码与 reason 字段，
 * 保证非法状态、归属不符、配额超限、资源不足等结论在各入口下互不混淆。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(OrchestrationException.class)
    public ResponseEntity<Map<String, Object>> handle(OrchestrationException ex) {
        log.warn("request rejected: reason={}, message={}", ex.getReason(), ex.getMessage());
        HttpStatus status = mapStatus(ex.getReason());
        Map<String, Object> body = Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "reason", ex.getReason().name(),
                "message", ex.getMessage()
        );
        return ResponseEntity.status(status).body(body);
    }

    /** 不同拒绝原因映射为确定且可区分的 HTTP 状态码。 */
    private HttpStatus mapStatus(RejectionReason reason) {
        return switch (reason) {
            case INVALID_REQUEST, INVALID_CONFIG -> HttpStatus.BAD_REQUEST;
            case IDEMPOTENCY_CONFLICT, LEASE_EXPIRED, ILLEGAL_STATE_TRANSITION -> HttpStatus.CONFLICT;
            case NOT_OWNER -> HttpStatus.FORBIDDEN;
            case IDEMPOTENCY_KEY_NOT_FOUND, ENV_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case OWNER_QUOTA_EXCEEDED, TEAM_QUOTA_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
            case INSUFFICIENT_RESOURCES -> HttpStatus.SERVICE_UNAVAILABLE;
            case PROVISION_FAILED -> HttpStatus.BAD_GATEWAY;
            case RETRY_LIMIT_EXHAUSTED -> HttpStatus.CONFLICT;
        };
    }
}
