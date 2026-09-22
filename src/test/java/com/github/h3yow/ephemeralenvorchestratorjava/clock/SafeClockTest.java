package com.github.h3yow.ephemeralenvorchestratorjava.clock;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时钟安全测试：系统时间回拨时，过期判定所依据的时钟不得倒退，
 * 从而保证有效租约不会被提前回收。
 */
class SafeClockTest {

    private static final Logger log = LoggerFactory.getLogger(SafeClockTest.class);

    @Test
    void clockNeverMovesBackwardsOnWallClockRollback() {
        AtomicLong source = new AtomicLong(1_000_000L);
        SafeClock clock = new SafeClock(source::get);

        Instant first = clock.now();
        source.set(900_000L); // 模拟系统时间回拨 100 秒
        Instant afterRollback = clock.now();
        log.info("[时钟回拨] 输入: 首次={}, 回拨后源时间={}, 判定: now={} 不得早于首次",
                first, Instant.ofEpochMilli(900_000L), afterRollback);

        assertEquals(first, afterRollback, "回拨后必须沿用上一次观测值，时间不得倒退");

        source.set(1_200_000L); // 时间恢复并超过此前观测值
        Instant advanced = clock.now();
        log.info("[时钟恢复] 输入: 源时间={}, 判定: now={} 必须前进", Instant.ofEpochMilli(1_200_000L), advanced);
        assertTrue(advanced.isAfter(first), "超过历史观测值后时间应继续前进");
        assertEquals(Instant.ofEpochMilli(1_200_000L), advanced);
    }
}
