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

    /**
     * 转让配额水位迁移：源归属释放一个、目标归属占用一个，按维度实际差异增减，
     * owner 相同或 team 相同时该维度不计数（不重复 +1/-1），因此：
     * <ul>
     *   <li>owner 与 team 都不同：源 owner/team 各 -1，目标 owner/team 各 +1；</li>
     *   <li>仅 owner 变化（同一团队内换人）：只动 owner 维度，团队水位保持不变；</li>
     *   <li>仅 team 变化（同一人换团队）：只动 team 维度，个人水位保持不变；</li>
     *   <li>owner 与 team 都相同（自己转自己）：两边都不动，不可能凭空增减配额。</li>
     * </ul>
     * 必须由编排服务在全局锁内、且已通过目标配额检查后调用，
     * 与租约归属变更在同一临界区一次性生效，杜绝半更新状态。
     */
    public void moveOwnership(String fromOwner, String fromTeam,
                              String toOwner, String toTeam) {
        if (!fromOwner.equals(toOwner)) {
            long from = ownerUsed.getOrDefault(fromOwner, 0L);
            if (from > 0) {
                ownerUsed.put(fromOwner, from - 1);
            }
            ownerUsed.merge(toOwner, 1L, Long::sum);
        }
        if (!fromTeam.equals(toTeam)) {
            long from = teamUsed.getOrDefault(fromTeam, 0L);
            if (from > 0) {
                teamUsed.put(fromTeam, from - 1);
            }
            teamUsed.merge(toTeam, 1L, Long::sum);
        }
    }
}
