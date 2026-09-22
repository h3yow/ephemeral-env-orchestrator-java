package com.github.h3yow.ephemeralenvorchestratorjava.config;

import com.github.h3yow.ephemeralenvorchestratorjava.clock.SafeClock;
import com.github.h3yow.ephemeralenvorchestratorjava.provision.LocalResourceProvisioner;
import com.github.h3yow.ephemeralenvorchestratorjava.provision.ResourceProvisioner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本地基础设施 Bean：单调安全时钟与固定容量资源池。
 */
@Configuration
public class OrchestratorBeans {

    @Bean
    public SafeClock safeClock() {
        return new SafeClock();
    }

    @Bean
    public ResourceProvisioner resourceProvisioner(OrchestratorProperties properties) {
        return new LocalResourceProvisioner(properties.getResources().getPoolSize());
    }
}
