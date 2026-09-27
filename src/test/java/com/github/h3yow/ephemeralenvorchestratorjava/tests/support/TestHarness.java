package com.github.h3yow.ephemeralenvorchestratorjava.tests.support;

import com.github.h3yow.ephemeralenvorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.ephemeralenvorchestratorjava.lease.LeaseRepository;
import com.github.h3yow.ephemeralenvorchestratorjava.quota.QuotaLedger;
import com.github.h3yow.ephemeralenvorchestratorjava.service.OrchestratorService;
import com.github.h3yow.ephemeralenvorchestratorjava.time.ManualTimeSource;

import java.time.Duration;
import java.time.Instant;

/**
 * 手工装配的测试夹具：不启动 Spring 容器，直接构造服务并注入可控时钟与脚本供给器，
 * 便于精确控制时间与供给结果。默认 owner 配额=2、team 配额=10、最大尝试 3 次。
 */
public class TestHarness {

    public final ManualTimeSource clock;
    public final ScriptedProvisioner provisioner;
    public final OrchestratorService service;
    public final OrchestratorProperties properties;
    public final QuotaLedger ledger;
    public final LeaseRepository repository;

    public TestHarness() {
        this(2L, 10L);
    }

    public TestHarness(long ownerQuota, long teamQuota) {
        properties = new OrchestratorProperties();
        properties.setDefaultTtl(Duration.ofMinutes(10));
        properties.setRenewalExtension(Duration.ofMinutes(5));
        properties.setMinTtl(Duration.ofMinutes(1));
        properties.setMaxTtl(Duration.ofMinutes(60));
        properties.setOwnerQuota(ownerQuota);
        properties.setTeamQuota(teamQuota);
        properties.setMaxProvisionAttempts(3);
        properties.setResourcePoolCapacity(100);
        properties.setReaperInterval(Duration.ofSeconds(30));
        properties.validate();

        clock = new ManualTimeSource(Instant.parse("2026-09-22T00:00:00Z"));
        provisioner = new ScriptedProvisioner();
        ledger = new QuotaLedger();
        ledger.configure(ownerQuota, teamQuota);
        LeaseRepository repository = new LeaseRepository();
        this.repository = repository;
        service = new OrchestratorService(repository, ledger, provisioner, clock, properties);
    }
}
