package com.github.h3yow.ephemeralenvorchestratorjava.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 续租/终止/重试等操作者请求：用于归属（owner）校验，跨所有者操作必须拒绝。
 */
public class OperationRequest {

    @NotBlank
    private String operator;

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
}
