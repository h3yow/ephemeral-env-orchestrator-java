package com.github.h3yow.ephemeralenvorchestratorjava.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 到期回收后台扫描。固定延迟、单线程执行，且与所有写操作竞争同一把编排锁，
 * 因此扫描回收与续租/终止并发时结论确定：不会重复回收、不会延长已结束租约。
 */
@Component
public class ExpirySweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpirySweepScheduler.class);

    private final EnvironmentOrchestrator orchestrator;

    public ExpirySweepScheduler(EnvironmentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /** 每固定毫秒扫描一次，用 orchestrator.sweep.fixed-delay-ms 覆盖（默认 60000，必须为正）。 */
    @Scheduled(fixedDelayString = "${orchestrator.sweep.fixed-delay-ms:60000}")
    public void sweep() {
        int reclaimed = orchestrator.sweepExpired();
        if (reclaimed > 0) {
            log.info("后台到期扫描回收环境数量={}", reclaimed);
        }
    }
}
