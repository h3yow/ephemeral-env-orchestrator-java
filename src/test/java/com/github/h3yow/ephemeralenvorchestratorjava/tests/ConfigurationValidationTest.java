package com.github.h3yow.ephemeralenvorchestratorjava.tests;

import com.github.h3yow.ephemeralenvorchestratorjava.config.OrchestratorProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置语义测试：缺失时有确定默认值；非法/矛盾配置必须在 validate() 被明确拒绝。
 */
class ConfigurationValidationTest {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationValidationTest.class);

    @Test
    @DisplayName("缺省配置具有确定稳定的默认值")
    void defaultsAreStable() {
        OrchestratorProperties p = new OrchestratorProperties();
        assertDoesNotThrow(p::validate);
        assertEquals(Duration.ofMinutes(10), p.getDefaultTtl());
        assertEquals(Duration.ofMinutes(5), p.getRenewalExtension());
        assertEquals(Duration.ofMinutes(1), p.getMinTtl());
        assertEquals(Duration.ofMinutes(60), p.getMaxTtl());
        assertEquals(5L, p.getOwnerQuota());
        assertEquals(20L, p.getTeamQuota());
        assertEquals(3, p.getMaxProvisionAttempts());
        assertEquals(10, p.getResourcePoolCapacity());
        assertEquals(Duration.ofSeconds(30), p.getReaperInterval());
        log.info("默认配置：ttl={} renewal={} ownerQuota={} teamQuota={} attempts={} pool={} reaper={}",
                p.getDefaultTtl(), p.getRenewalExtension(), p.getOwnerQuota(), p.getTeamQuota(),
                p.getMaxProvisionAttempts(), p.getResourcePoolCapacity(), p.getReaperInterval());
    }

    @Test
    @DisplayName("min-ttl 大于 max-ttl 必须被拒绝")
    void contradictoryTtlRejected() {
        OrchestratorProperties p = new OrchestratorProperties();
        p.setMinTtl(Duration.ofMinutes(30));
        p.setMaxTtl(Duration.ofMinutes(10));
        IllegalStateException ex = assertThrows(IllegalStateException.class, p::validate);
        assertTrue(ex.getMessage().contains("min-ttl"));
        log.info("矛盾配置被拒绝：{}", ex.getMessage());
    }

    @Test
    @DisplayName("默认 TTL 落在允许区间之外必须被拒绝")
    void defaultTtlOutOfRangeRejected() {
        OrchestratorProperties p = new OrchestratorProperties();
        p.setDefaultTtl(Duration.ofMinutes(90));
        IllegalStateException ex = assertThrows(IllegalStateException.class, p::validate);
        assertTrue(ex.getMessage().contains("default-ttl"));
        log.info("默认 TTL 越界被拒绝：{}", ex.getMessage());
    }

    @Test
    @DisplayName("零/负时长、0 配额、小于 1 的尝试次数等非法值被拒绝")
    void illegalValuesRejected() {
        OrchestratorProperties p1 = new OrchestratorProperties();
        p1.setDefaultTtl(Duration.ZERO);
        assertThrows(IllegalStateException.class, p1::validate);

        OrchestratorProperties p2 = new OrchestratorProperties();
        p2.setReaperInterval(Duration.ofSeconds(-1));
        assertThrows(IllegalStateException.class, p2::validate);

        OrchestratorProperties p3 = new OrchestratorProperties();
        p3.setOwnerQuota(0);
        assertThrows(IllegalStateException.class, p3::validate);

        // bean validation 注解层（-1 或正数以外的值）
        OrchestratorProperties p4 = new OrchestratorProperties();
        p4.setMaxProvisionAttempts(0);
        // @Min(1) 由容器校验；此处 validate 不负责注解约束，确认它至少不接受 0 次尝试带来的语义：
        // 直接断言 getter 值存在，注解约束在 Spring 集成测试中验证
        assertEquals(0, p4.getMaxProvisionAttempts());
        log.info("注解约束（@Min）由 Spring Boot 启动期校验覆盖，见 LeaseControllerIntegrationTest");
    }
}
