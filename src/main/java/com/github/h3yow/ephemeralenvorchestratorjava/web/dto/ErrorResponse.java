package com.github.h3yow.ephemeralenvorchestratorjava.web.dto;

import java.time.Instant;

/**
 * 统一错误响应。code 为稳定机器可读原因码，message 为可解释描述。
 */
public record ErrorResponse(
        String code,
        String message,
        Instant timestamp,
        String path
) {
}
