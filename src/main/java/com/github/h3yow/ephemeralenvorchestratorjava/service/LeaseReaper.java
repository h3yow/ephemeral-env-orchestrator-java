package com.github.h3yow.ephemeralenvorchestratorjava.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 过期回收定时任务。固定延迟由配置 orchestrator.reaper-interval 决定。
 *
 * <p>真正的“是否到期”判定与状态翻转在服务的全局锁内完成，与并发续租严格串行：
 * 续租与回收针对同一个到期边界只有一方胜出，结论确定且原因可区分。</p>
 */
@Component
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final OrchestratorService service;

    public LeaseReaper(OrchestratorService service) {
        this.service = service;
    }

    /**
     * 固定延迟（毫秒），由 orchestrator.reaper-interval 绑定为可解析的 Duration 毫秒值。
     */
    @Scheduled(fixedDelayString = "#{T(java.time.Duration).parse('${orchestrator.reaper-interval:PT30S}').toMillis()}")
    public void reap() {
        try {
            int n = service.reapExpired();
            if (n > 0) {
                log.info("定时过期回收本轮处理租约数={}", n);
            }
        } catch (RuntimeException ex) {
            // 回收任务异常不能中断调度线程
            log.error("定时过期回收发生异常: {}", ex.getMessage(), ex);
        }
    }
}
