package com.github.h3yow.ephemeralenvorchestratorjava.quota;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配额账本：维护 owner 级与 team 级两套计数。
 *
 * <p>占用口径：申请受理即计数（PENDING/PROVISIONING/READY/可重试 FAILED 都占配额），
 * 终态（TERMINATED/EXPIRED/达到上限的 FAILED）释放。供给失败在仍可重试期间继续占配额，
 * 避免“失败即释放”导致无限重试冲击资源池，也防止失败长期占用：达到上限立即释放。</p>
 *
 * <p>{@link #tryAcquire} 自身原子；编排服务在其全局锁内调用，与状态变更共同串行化，
 * 因而不会超卖。release 多余调用由 Lease 的幂等标志在外层拦截。</p>
 */
@Component
public class QuotaLedger {

    private static final long UNLIMITED = -1L;

    private final ConcurrentHashMap<String, Long> ownerUsed = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> teamUsed = new ConcurrentHashMap<>();

    private volatile long ownerLimit = UNLIMITED;
    private volatile long teamLimit = UNLIMITED;

    public void configure(long ownerLimit, long teamLimit) {
        if (ownerLimit == 0 || teamLimit == 0) {
            throw new IllegalArgumentException("配额上限必须为 -1（不限）或正整数，不能为 0");
        }
        this.ownerLimit = ownerLimit;
        this.teamLimit = teamLimit;
    }

    /**
     * 无条件占用一个配额（owner 与 team 同时 +1）。调用方必须已在同一全局临界区内
     * 通过 {@link #ownerView}/{@link #teamView} 完成未满检查，从而保证检查与占用原子。
     */
    public void acquire(String owner, String team) {
        synchronized (owner.intern()) {
            ownerUsed.merge(owner, 1L, Long::sum);
            teamUsed.merge(team, 1L, Long::sum);
        }
    }

    public void release(String owner, String team) {
        synchronized (owner.intern()) {
            long o = ownerUsed.getOrDefault(owner, 0L);
            if (o > 0) {
                ownerUsed.put(owner, o - 1);
            }
            long t = teamUsed.getOrDefault(team, 0L);
            if (t > 0) {
                teamUsed.put(team, t - 1);
            }
        }
    }

    public QuotaView ownerView(String owner) {
        return new QuotaView(ownerUsed.getOrDefault(owner, 0L), ownerLimit);
    }

    public QuotaView teamView(String team) {
        return new QuotaView(teamUsed.getOrDefault(team, 0L), teamLimit);
    }

    public Map<String, QuotaView> allOwnerViews() {
        Map<String, QuotaView> views = new HashMap<>();
        ownerUsed.forEach((k, v) -> views.put(k, new QuotaView(v, ownerLimit)));
        return views;
    }

    public Map<String, QuotaView> allTeamViews() {
        Map<String, QuotaView> views = new HashMap<>();
        teamUsed.forEach((k, v) -> views.put(k, new QuotaView(v, teamLimit)));
        return views;
    }
}
