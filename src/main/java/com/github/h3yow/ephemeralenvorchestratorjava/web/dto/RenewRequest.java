package com.github.h3yow.ephemeralenvorchestratorjava.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Duration;

/**
 * 续租请求。extension 为空时使用配置的默认续租时长。
 */
public class RenewRequest {

    @NotBlank
    private String operator;

    /** ISO-8601 时长，如 PT5M；可空。 */
    private String extension;

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getExtension() { return extension; }
    public void setExtension(String extension) { this.extension = extension; }

    public Duration parsedExtension() {
        return null;
    }
}
