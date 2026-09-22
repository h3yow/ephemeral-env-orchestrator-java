package com.github.h3yow.ephemeralenvorchestratorjava.service;

import com.github.h3yow.ephemeralenvorchestratorjava.clock.SafeClock;
import com.github.h3yow.ephemeralenvorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.ephemeralenvorchestratorjava.provision.LocalResourceProvisioner;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试辅助：以确定性的小配额、受控时钟构造编排器与资源供给器。
 */
final class TestSupport {

    private TestSupport() {
    }

    static OrchestratorProperties props(int ownerQuota, int teamQuota,
                                        long ttlSeconds, long extendSeconds, int maxAttempts, int poolSize) {
        OrchestratorProperties p = new OrchestratorProperties();
        p.getDefaults().setOwnerQuota(ownerQuota);
        p.getDefaults().setTeamQuota(teamQuota);
        p.getLease().setTtlSeconds(ttlSeconds);
        p.getLease().setExtendSeconds(extendSeconds);
        p.getProvision().setMaxAttempts(maxAttempts);
        p.getResources().setPoolSize(poolSize);
        return p;
    }

    static OrchestratorProperties defaults() {
        return props(3, 8, 300, 300, 3, 20);
    }

    /** 可控毫秒时间源的时钟。 */
    static SafeClock controlledClock(AtomicLong millis) {
        return new SafeClock(millis::get);
    }

    static LocalResourceProvisioner provisioner(int poolSize) {
        return new LocalResourceProvisioner(poolSize);
    }
}
