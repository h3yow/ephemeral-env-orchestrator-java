package com.github.h3yow.ephemeralenvorchestratorjava.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Duration;

/**
 * 申请请求。requestId 为调用方幂等键；ttl 为空时采用配置默认值。
 */
public class ApplyLeaseRequest {

    @NotBlank
    private String requestId;

    @NotBlank
    private String owner;

    @NotBlank
    private String team;

    /** ISO-8601 时长，如 PT10M；可空。 */
    @Pattern(regexp = "^P.*", message = "ttl 必须为 ISO-8601 时长，例如 PT10M")
    private String ttl;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getTeam() { return team; }
    public void setTeam(String team) { this.team = team; }
    public String getTtl() { return ttl; }
    public void setTtl(String ttl) { this.ttl = ttl; }

    /** 骨架辅助方法：解析 ttl，后续在服务层填充并校验边界。 */
    public Duration parsedTtl() {
        return null;
    }
}
