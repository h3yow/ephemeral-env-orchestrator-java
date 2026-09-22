package com.github.h3yow.ephemeralenvorchestratorjava.tests;

import com.github.h3yow.ephemeralenvorchestratorjava.Application;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 非法配置必须在启动期被明确拒绝，而不是静默忽略：
 * 既覆盖 validate() 的一致性校验，也覆盖 @Min 等注解约束。
 *
 * <p>注意：测试通过命令行参数（优先级高于 application.properties）覆盖默认值，
 * 与“java -jar --orchestrator.xxx=...”的真实运维方式一致；
 * 不能用 builder.properties(...)——那是 defaultProperties，会被打包的
 * application.properties 显式默认值盖掉。</p>
 */
class InvalidConfigBootRejectTest {

    private static final Logger log = LoggerFactory.getLogger(InvalidConfigBootRejectTest.class);

    private static Exception bootFailure(String... args) {
        return assertThrows(Exception.class, () -> {
            var ctx = new SpringApplicationBuilder(Application.class)
                    .web(WebApplicationType.NONE)
                    .run(args);
            ctx.close();
        });
    }

    @Test
    @DisplayName("矛盾配置 min-ttl>max-ttl 阻止启动")
    void contradictoryTtlStopsBoot() {
        Exception boot = bootFailure(
                "--orchestrator.min-ttl=PT30M",
                "--orchestrator.max-ttl=PT10M");
        assertTrue(hasCauseContaining(boot, "min-ttl"),
                "判定依据：异常链应指向 min-ttl 与 max-ttl 矛盾，实际=" + rootMessage(boot));
        log.info("矛盾配置启动失败：{}", rootMessage(boot));
    }

    @Test
    @DisplayName("@Min 注解约束：owner-quota=-2 绑定失败阻止启动")
    void annotationConstraintStopsBoot() {
        Exception boot = bootFailure("--orchestrator.owner-quota=-2");
        assertNotNull(boot);
        log.info("非法配额启动失败：{}", rootMessage(boot));
    }

    @Test
    @DisplayName("零时长阻止启动")
    void nonPositiveDurationStopsBoot() {
        Exception boot = bootFailure("--orchestrator.default-ttl=PT0S");
        assertTrue(hasCauseContaining(boot, "default-ttl"),
                "判定依据：异常链应指向 default-ttl 非正，实际=" + rootMessage(boot));
        log.info("零时长启动失败：{}", rootMessage(boot));
    }

    private static boolean hasCauseContaining(Throwable t, String fragment) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null && c.getMessage().contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
