package com.github.h3yow.ephemeralenvorchestratorjava.config;

import com.github.h3yow.ephemeralenvorchestratorjava.quota.QuotaLedger;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * 应用装配：启动时显式校验配置并初始化配额账本，非法配置快速失败（拒绝启动）。
 */
@Configuration
public class AppConfig {

    private final OrchestratorProperties properties;
    private final QuotaLedger ledger;

    public AppConfig(OrchestratorProperties properties, QuotaLedger ledger) {
        this.properties = properties;
        this.ledger = ledger;
    }

    @PostConstruct
    public void init() {
        properties.validate();
        ledger.configure(properties.getOwnerQuota(), properties.getTeamQuota());
    }
}
