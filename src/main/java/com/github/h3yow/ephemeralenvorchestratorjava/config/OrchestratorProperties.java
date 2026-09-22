package com.github.h3yow.ephemeralenvorchestratorjava.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 编排服务配置。
 *
 * <p>缺失配置项时使用确定稳定的默认值（见各字段初始值）；非法配置在
 * {@link #validate()} 启动校验中被明确拒绝（抛异常导致启动失败），绝不静默忽略。</p>
 *
 * <ul>
 *   <li>orchestrator.defaults.owner-quota —— 未显式声明的所有者配额（默认 3）</li>
 *   <li>orchestrator.defaults.team-quota —— 未显式声明的团队配额（默认 8）</li>
 *   <li>orchestrator.lease.ttl-seconds —— 首次就绪租约时长（默认 300s）</li>
 *   <li>orchestrator.lease.extend-seconds —— 每次续租延长时长（默认 300s）</li>
 *   <li>orchestrator.provision.max-attempts —— 供给最大尝试次数（默认 3）</li>
 *   <li>orchestrator.resources.pool-size —— 资源池总量（默认 20）</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "orchestrator")
public class OrchestratorProperties {

    private final Defaults defaults = new Defaults();
    private final Lease lease = new Lease();
    private final Provision provision = new Provision();
    private final Resources resources = new Resources();
    private final Sweep sweep = new Sweep();

    public Defaults getDefaults() {
        return defaults;
    }

    public Lease getLease() {
        return lease;
    }

    public Provision getProvision() {
        return provision;
    }

    public Resources getResources() {
        return resources;
    }

    public Sweep getSweep() {
        return sweep;
    }

    /**
     * 启动期配置校验：非法配置明确拒绝（抛出异常使应用启动失败），绝不静默忽略。
     * 缺失的配置项由字段初始值 / application.properties 提供确定稳定的默认语义。
     */
    @PostConstruct
    void validate() {
        if (defaults.ownerQuota <= 0) {
            throw new IllegalArgumentException(
                    "orchestrator.defaults.owner-quota 必须为正整数，实际值: " + defaults.ownerQuota);
        }
        if (defaults.teamQuota <= 0) {
            throw new IllegalArgumentException(
                    "orchestrator.defaults.team-quota 必须为正整数，实际值: " + defaults.teamQuota);
        }
        if (lease.ttlSeconds <= 0) {
            throw new IllegalArgumentException(
                    "orchestrator.lease.ttl-seconds 必须为正整数，实际值: " + lease.ttlSeconds);
        }
        if (lease.extendSeconds <= 0) {
            throw new IllegalArgumentException(
                    "orchestrator.lease.extend-seconds 必须为正整数，实际值: " + lease.extendSeconds);
        }
        if (provision.maxAttempts <= 0) {
            throw new IllegalArgumentException(
                    "orchestrator.provision.max-attempts 必须为正整数，实际值: " + provision.maxAttempts);
        }
        if (resources.poolSize <= 0) {
            throw new IllegalArgumentException(
                    "orchestrator.resources.pool-size 必须为正整数，实际值: " + resources.poolSize);
        }
        if (sweep.fixedDelayMs <= 0) {
            throw new IllegalArgumentException(
                    "orchestrator.sweep.fixed-delay-ms 必须为正整数，实际值: " + sweep.fixedDelayMs);
        }
    }

    public static class Defaults {
        private int ownerQuota = 3;
        private int teamQuota = 8;

        public int getOwnerQuota() {
            return ownerQuota;
        }

        public void setOwnerQuota(int ownerQuota) {
            this.ownerQuota = ownerQuota;
        }

        public int getTeamQuota() {
            return teamQuota;
        }

        public void setTeamQuota(int teamQuota) {
            this.teamQuota = teamQuota;
        }
    }

    public static class Lease {
        private long ttlSeconds = 300;
        private long extendSeconds = 300;

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public long getExtendSeconds() {
            return extendSeconds;
        }

        public void setExtendSeconds(long extendSeconds) {
            this.extendSeconds = extendSeconds;
        }
    }

    public static class Provision {
        private int maxAttempts = 3;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }

    public static class Resources {
        private int poolSize = 20;

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }
    }

    /** 后台到期扫描配置。 */
    public static class Sweep {
        private long fixedDelayMs = 60_000;

        public long getFixedDelayMs() {
            return fixedDelayMs;
        }

        public void setFixedDelayMs(long fixedDelayMs) {
            this.fixedDelayMs = fixedDelayMs;
        }
    }
}
