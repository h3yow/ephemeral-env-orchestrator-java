package com.github.h3yow.ephemeralenvorchestratorjava.error;

/**
 * 可预期的业务拒绝异常。携带稳定的 {@link ErrorCode} 与可解释的上下文信息，
 * 由全局异常处理器映射为统一错误响应，不向调用方泄露内部实现细节。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;

    public BusinessException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
