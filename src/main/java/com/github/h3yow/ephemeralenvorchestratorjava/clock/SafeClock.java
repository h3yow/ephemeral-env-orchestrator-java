package com.github.h3yow.ephemeralenvorchestratorjava.clock;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 单调安全时钟。
 *
 * <p>过期判定不得因系统时间回拨而提前回收有效租约：本时钟维护“已观测到的最大时间”，
 * 当底层时钟回拨（NTP 校时、人为调整）时返回此前的最大值，保证时间只会向前、不会倒退。
 * 已经被回收的环境不会因时间“倒流”而重新变回 READY（其终态由状态机本身保证）。</p>
 */
public class SafeClock {

    private final LongSupplier epochMillisSource;
    private final AtomicLong lastObservedMillis;

    /** 生产构造：基于 {@link System#currentTimeMillis()}。 */
    public SafeClock() {
        this(System::currentTimeMillis);
    }

    /** 测试构造：可注入受控时间源（测试可模拟回拨）。 */
    public SafeClock(LongSupplier epochMillisSource) {
        this.epochMillisSource = epochMillisSource;
        this.lastObservedMillis = new AtomicLong(epochMillisSource.getAsLong());
    }

    /**
     * 返回不会回退的当前时刻。
     *
     * <p>判定依据：若底层时间小于上次观测值（发生回拨），沿用上一次观测值，
     * 因此有效租约绝不会因回拨被提前判定到期。</p>
     */
    public Instant now() {
        long observed = epochMillisSource.getAsLong();
        long effective = lastObservedMillis.accumulateAndGet(observed, Math::max);
        return Instant.ofEpochMilli(effective);
    }
}
