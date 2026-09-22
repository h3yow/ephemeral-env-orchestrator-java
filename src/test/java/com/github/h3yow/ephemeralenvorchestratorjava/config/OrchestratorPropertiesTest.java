package com.github.h3yow.ephemeralenvorchestratorjava.config;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 配置测试：缺失配置有稳定默认值；非法配置（0 或负数）被明确拒绝而非静默忽略。
 */
class OrchestratorPropertiesTest {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorPropertiesTest.class);

    private OrchestratorProperties bind(Map<String, String> values) {
        var source = new MapConfigurationPropertySource(values);
        return new Binder(source).bindOrCreate("orchestrator", OrchestratorProperties.class);
    }

    @Test
    void missingConfigUsesStableDefaults() {
        OrchestratorProperties p = bind(Map.of());
        log.info("[默认配置] 输入: 空配置, 判定: ownerQuota={}, teamQuota={}, ttl={}, extend={}, maxAttempts={}, poolSize={}",
                p.getDefaults().getOwnerQuota(), p.getDefaults().getTeamQuota(),
                p.getLease().getTtlSeconds(), p.getLease().getExtendSeconds(),
                p.getProvision().getMaxAttempts(), p.getResources().getPoolSize());
        assertEquals(3, p.getDefaults().getOwnerQuota());
        assertEquals(8, p.getDefaults().getTeamQuota());
        assertEquals(300, p.getLease().getTtlSeconds());
        assertEquals(300, p.getLease().getExtendSeconds());
        assertEquals(3, p.getProvision().getMaxAttempts());
        assertEquals(20, p.getResources().getPoolSize());
        assertEquals(60_000L, p.getSweep().getFixedDelayMs());
    }

    @Test
    void invalidSweepDelayIsRejected() {
        assertIllegal(Map.of("orchestrator.sweep.fixed-delay-ms", "0"), "sweep.fixed-delay-ms=0");
    }

    @Test
    void invalidConfigIsRejectedAtValidation() {
        assertIllegal(Map.of("orchestrator.defaults.owner-quota", "0"), "owner-quota=0");
        assertIllegal(Map.of("orchestrator.defaults.team-quota", "-1"), "team-quota=-1");
        assertIllegal(Map.of("orchestrator.lease.ttl-seconds", "0"), "ttl=0");
        assertIllegal(Map.of("orchestrator.provision.max-attempts", "0"), "max-attempts=0");
        assertIllegal(Map.of("orchestrator.resources.pool-size", "-5"), "pool-size=-5");
    }

    @Test
    void nonNumericConfigIsRejectedAtBinding() {
        // 类型完全不匹配时绑定阶段就失败，同样是明确拒绝而非静默忽略
        assertThrows(BindException.class,
                () -> bind(Map.of("orchestrator.defaults.owner-quota", "abc")));
        log.info("[非法配置类型] 输入: owner-quota=abc, 判定: 绑定阶段抛 BindException，不静默忽略");
    }

    private void assertIllegal(Map<String, String> values, String label) {
        OrchestratorProperties p = bind(values);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, p::validate);
        log.info("[非法配置拒绝] 输入: {}, 判定: 启动校验抛出异常 message={}", label, ex.getMessage());
    }
}
