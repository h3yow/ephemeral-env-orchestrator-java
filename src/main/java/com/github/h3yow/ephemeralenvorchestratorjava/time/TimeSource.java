package com.github.h3yow.ephemeralenvorchestratorjava.time;

import java.time.Instant;

/**
 * 时间源抽象：所有过期判定都必须经由该抽象，测试中可注入手动时钟模拟回拨与流逝。
 */
public interface TimeSource {
    Instant now();
}
