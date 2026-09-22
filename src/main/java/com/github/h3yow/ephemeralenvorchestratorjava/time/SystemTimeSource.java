package com.github.h3yow.ephemeralenvorchestratorjava.time;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 基于系统时钟的默认实现（Instant.now 使用 UTC）。
 */
@Component
public class SystemTimeSource implements TimeSource {
    @Override
    public Instant now() {
        return Instant.now();
    }
}
