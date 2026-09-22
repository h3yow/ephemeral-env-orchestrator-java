package com.github.h3yow.ephemeralenvorchestratorjava.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 编排服务配置（前缀 orchestrator）。
 *
 * <p>默认语义确定且稳定：任何配置项缺失时使用此处声明的默认值；非法值（负数/空值/
 * 相互矛盾的组合）由 {@link #validate()} 在启动时明确拒绝，绝不静默忽略。</p>
 */
@Validated
@ConfigurationProperties(prefix = "orchestrator")
public class OrchestratorProperties {

    /** 新租约默认有效期（缺省 10 分钟）。 */
    @NotNull
    private Duration defaultTtl = Duration.ofMinutes(10);

    /** 单次续租延长时长（缺省 5 分钟）。 */
    @NotNull
    private Duration renewalExtension = Duration.ofMinutes(5);

    /** 申请时允许显式指定的最小 TTL（缺省 1 分钟）。 */
    @NotNull
    private Duration minTtl = Duration.ofMinutes(1);

    /** 申请时允许显式指定的最大 TTL（缺省 60 分钟）。 */
    @NotNull
    private Duration maxTtl = Duration.ofMinutes(60);

    /** 单个 owner 同时持有租约上限（-1 表示不限，缺省 5）。 */
    @Min(-1)
    private long ownerQuota = 5L;

    /** 单个 team 同时持有租约上限（-1 表示不限，缺省 20）。 */
    @Min(-1)
    private long teamQuota = 20L;

    /** 供给最大尝试次数（含首次），达到上限后 FAILED 为终态（缺省 3）。 */
    @Min(1)
    private int maxProvisionAttempts = 3;

    /** 模拟资源池容量（资源不足判定依据，缺省 10）。 */
    @Min(0)
    private int resourcePoolCapacity = 10;

    /** 过期回收扫描间隔（缺省 30 秒）。 */
    @NotNull
    private Duration reaperInterval = Duration.ofSeconds(30);

    /**
     * 启动时校验配置一致性，非法配置抛出异常阻止应用启动（明确拒绝，绝不静默忽略）。
     */
    public void validate() {
        requirePositive(defaultTtl, "orchestrator.default-ttl");
        requirePositive(renewalExtension, "orchestrator.renewal-extension");
        requirePositive(minTtl, "orchestrator.min-ttl");
        requirePositive(maxTtl, "orchestrator.max-ttl");
        requirePositive(reaperInterval, "orchestrator.reaper-interval");
        if (minTtl.compareTo(maxTtl) > 0) {
            throw new IllegalStateException("非法配置：orchestrator.min-ttl(" + minTtl
                    + ") 不能大于 orchestrator.max-ttl(" + maxTtl + ")");
        }
        if (defaultTtl.compareTo(minTtl) < 0 || defaultTtl.compareTo(maxTtl) > 0) {
            throw new IllegalStateException("非法配置：orchestrator.default-ttl(" + defaultTtl
                    + ") 必须落在 [" + minTtl + "," + maxTtl + "] 区间内");
        }
        if (ownerQuota == 0 || teamQuota == 0) {
            throw new IllegalStateException("非法配置：配额必须为 -1（不限）或正整数，不能为 0");
        }
    }

    private static void requirePositive(Duration value, String key) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("非法配置：" + key + " 必须为正的时长，实际=" + value);
        }
    }

    // 骨架阶段的 getter/setter（IDE 风格，后续不变）
    public Duration getDefaultTtl() { return defaultTtl; }
    public void setDefaultTtl(Duration defaultTtl) { this.defaultTtl = defaultTtl; }
    public Duration getRenewalExtension() { return renewalExtension; }
    public void setRenewalExtension(Duration renewalExtension) { this.renewalExtension = renewalExtension; }
    public Duration getMinTtl() { return minTtl; }
    public void setMinTtl(Duration minTtl) { this.minTtl = minTtl; }
    public Duration getMaxTtl() { return maxTtl; }
    public void setMaxTtl(Duration maxTtl) { this.maxTtl = maxTtl; }
    public long getOwnerQuota() { return ownerQuota; }
    public void setOwnerQuota(long ownerQuota) { this.ownerQuota = ownerQuota; }
    public long getTeamQuota() { return teamQuota; }
    public void setTeamQuota(long teamQuota) { this.teamQuota = teamQuota; }
    public int getMaxProvisionAttempts() { return maxProvisionAttempts; }
    public void setMaxProvisionAttempts(int maxProvisionAttempts) { this.maxProvisionAttempts = maxProvisionAttempts; }
    public int getResourcePoolCapacity() { return resourcePoolCapacity; }
    public void setResourcePoolCapacity(int resourcePoolCapacity) { this.resourcePoolCapacity = resourcePoolCapacity; }
    public Duration getReaperInterval() { return reaperInterval; }
    public void setReaperInterval(Duration reaperInterval) { this.reaperInterval = reaperInterval; }
}
