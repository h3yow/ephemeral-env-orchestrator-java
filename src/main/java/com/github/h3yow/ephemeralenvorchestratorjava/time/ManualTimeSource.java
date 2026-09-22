package com.github.h3yow.ephemeralenvorchestratorjava.time;

import java.time.Duration;
import java.time.Instant;

/**
 * 测试用可控时钟。可显式设置/推进/回拨时间，用于复现并发续租与过期回收竞态。
 */
public class ManualTimeSource implements TimeSource {

    private volatile Instant current;

    public ManualTimeSource(Instant start) {
        this.current = start;
    }

    @Override
    public Instant now() {
        return current;
    }

    public void set(Instant t) {
        this.current = t;
    }

    public void advanceSeconds(long seconds) {
        this.current = current.plusSeconds(seconds);
    }

    public void advance(Duration d) {
        this.current = current.plus(d);
    }
}
