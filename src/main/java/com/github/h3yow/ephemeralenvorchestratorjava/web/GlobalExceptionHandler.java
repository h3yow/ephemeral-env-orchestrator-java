package com.github.h3yow.ephemeralenvorchestratorjava.web;

import com.github.h3yow.ephemeralenvorchestratorjava.error.BusinessException;
import com.github.h3yow.ephemeralenvorchestratorjava.error.ErrorCode;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * 统一异常映射：
 * <ul>
 *   <li>400：输入/参数校验失败（VALIDATION_ERROR）；</li>
 *   <li>404：租约不存在（LEASE_NOT_FOUND）；</li>
 *   <li>409：状态非法、配额不足、资源不足、跨所有者、时钟回拨等业务拒绝，
 *       彼此靠稳定的 errorCode 字段区分；</li>
 *   <li>500：未预期内部错误。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex, HttpServletRequest req) {
        HttpStatus status = switch (ex.getCode()) {
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            case LEASE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.CONFLICT;
        };
        log.warn("业务拒绝 path={} code={} message={}", req.getRequestURI(), ex.getCode(), ex.getMessage());
        return respond(status, ex.getCode().name(), ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest req) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败 path={} detail={}", req.getRequestURI(), detail);
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.name(), detail, req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
                                                               HttpServletRequest req) {
        log.warn("非法参数 path={} message={}", req.getRequestURI(), ex.getMessage());
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.name(),
                ex.getMessage(), req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex, HttpServletRequest req) {
        log.error("未预期异常 path={}", req.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "服务内部错误", req);
    }

    private static ResponseEntity<ErrorResponse> respond(HttpStatus status, String code,
                                                         String message, HttpServletRequest req) {
        ErrorResponse body = new ErrorResponse(code, message, Instant.now(), req.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
