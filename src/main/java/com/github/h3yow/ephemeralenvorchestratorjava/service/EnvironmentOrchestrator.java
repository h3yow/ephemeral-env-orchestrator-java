package com.github.h3yow.ephemeralenvorchestratorjava.service;

import com.github.h3yow.ephemeralenvorchestratorjava.clock.SafeClock;
import com.github.h3yow.ephemeralenvorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.EnvironmentStatus;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.FailureRecord;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.LeaseView;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.OrchestrationException;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.QuotaView;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.RejectionReason;
import com.github.h3yow.ephemeralenvorchestratorjava.provision.ProvisionOutcome;
import com.github.h3yow.ephemeralenvorchestratorjava.provision.ResourceProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 临时环境租约编排核心服务（内存状态）。
 *
 * <p>所有公共方法在同一把实例锁（{@code synchronized}）下完成“校验-判定-状态变更”全过程，
 * 保证并发申请、重复提交、续租/回收竞态下结论确定：不超卖配额、不重复释放、不发生状态跳变。
 * 配额计数以状态为准实时聚合（占用态：PENDING/PROVISIONING/READY，以及尚未耗尽重试的 FAILED；
 * 本实现中失败即释放配额，因此 FAILED 不计入占用）。</p>
 */
@Service
public class EnvironmentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentOrchestrator.class);

    private final OrchestratorProperties properties;
    private final SafeClock clock;
    private final ResourceProvisioner provisioner;

    /** envId -> 租约聚合（由本对象实例锁保护）。 */
    private final Map<String, Lease> leases = new LinkedHashMap<>();
    /** requestId -> envId，幂等索引。 */
    private final Map<String, String> requestIndex = new LinkedHashMap<>();

    public EnvironmentOrchestrator(OrchestratorProperties properties,
                                   SafeClock clock,
                                   ResourceProvisioner provisioner) {
        this.properties = properties;
        this.clock = clock;
        this.provisioner = provisioner;
    }

    // ============================ 申请 ============================

    /**
     * 申请并同步供给。
     * <ul>
     *   <li>同一 requestId 重复提交：返回首个请求的结果，duplicate=true，绝不重复分配；</li>
     *   <li>同一 requestId 但 owner/team 不同：IDEMPOTENCY_CONFLICT；</li>
     *   <li>所有者/团队配额超限：OWNER_QUOTA_EXCEEDED / TEAM_QUOTA_EXCEEDED。</li>
     * </ul>
     */
    public synchronized Commands.ApplyResult apply(Commands.ApplyCommand command) {
        if (command == null) {
            throw reject(RejectionReason.INVALID_REQUEST, "请求体为空");
        }
        String requestId = requireText(command.requestId(), "requestId");
        String owner = requireText(command.owner(), "owner");
        String team = command.team() == null || command.team().isBlank() ? owner : command.team().trim();

        log.info("apply 输入: requestId={}, owner={}, team={}", requestId, owner, team);

        Lease existing = leases.get(requestIndex.get(requestId));
        if (existing != null) {
            if (!existing.owner.equals(owner) || !existing.team.equals(team)) {
                throw reject(RejectionReason.IDEMPOTENCY_CONFLICT,
                        "requestId=" + requestId + " 已用于 owner=" + existing.owner
                                + ", team=" + existing.team + "，与本次请求不一致");
            }
            log.info("apply 判定: 幂等命中 requestId={}, envId={}, status={}，不重复分配",
                    requestId, existing.envId, existing.status);
            return new Commands.ApplyResult(existing.envId, true, existing.snapshot());
        }

        checkQuota(owner, team);

        Lease lease = new Lease("env-" + UUID.randomUUID(), requestId, owner, team, clock.now());
        leases.put(lease.envId, lease);
        requestIndex.put(requestId, lease.envId);
        log.info("apply 判定: 受理新申请 envId={}, requestId={}", lease.envId, requestId);

        provision(lease);
        return new Commands.ApplyResult(lease.envId, false, lease.snapshot());
    }

    // ============================ 续租 ============================

    /**
     * 续租 READY 环境。
     * <ul>
     *   <li>非所有者：NOT_OWNER；</li>
     *   <li>非 READY：ILLEGAL_STATE_TRANSITION；</li>
     *   <li>已到期：先执行回收（释放资源/配额），再以 LEASE_EXPIRED 拒绝续租；
     *       回收与续租并发时由同一把锁串行化，不会出现“已结束仍被续租”。</li>
     * </ul>
     */
    public synchronized LeaseView renew(Commands.RenewCommand command) {
        // 续租路径不做惰性回收：必须由本方法显式判定到期，才能给出可区分的 LEASE_EXPIRED，
        // 而不是被提前回收后报成 ILLEGAL_STATE_TRANSITION。
        Lease lease = leases.get(requireText(command == null ? null : command.envId(), "envId"));
        if (lease == null) {
            throw reject(RejectionReason.ENV_NOT_FOUND, "环境不存在: " + command.envId());
        }
        requireOwner(lease, command.operator());
        log.info("renew 输入: envId={}, operator={}, status={}, expiresAt={}, now={}",
                lease.envId, command.operator(), lease.status, lease.expiresAt, clock.now());

        if (lease.status != EnvironmentStatus.READY) {
            throw reject(RejectionReason.ILLEGAL_STATE_TRANSITION,
                    "仅 READY 环境可续租，当前状态=" + lease.status + "，envId=" + lease.envId);
        }
        if (isExpired(lease)) {
            expireLease(lease, "租约已于 " + lease.expiresAt + " 到期，续租请求触发回收");
            throw reject(RejectionReason.LEASE_EXPIRED,
                    "租约已过期并已回收，envId=" + lease.envId + "，expiresAt=" + lease.expiresAt);
        }

        Instant base = clock.now();
        lease.expiresAt = base.plusSeconds(properties.getLease().getExtendSeconds());
        lease.renewedCount++;
        log.info("renew 判定: 续租成功 envId={}, 依据 now={} 未过期，新 expiresAt={}, renewedCount={}",
                lease.envId, base, lease.expiresAt, lease.renewedCount);
        return lease.snapshot();
    }

    // ============================ 终止 ============================

    /**
     * 主动终止。READY/PENDING/PROVISIONING/未耗尽的 FAILED 可终止；
     * TERMINATED 重复终止幂等返回；EXPIRED 已被系统回收，再终止属非法流转。
     */
    public synchronized LeaseView terminate(Commands.TerminateCommand command) {
        Lease lease = requireLease(command == null ? null : command.envId());
        requireOwner(lease, command.operator());
        log.info("terminate 输入: envId={}, operator={}, status={}", lease.envId, command.operator(), lease.status);

        switch (lease.status) {
            case TERMINATED -> {
                log.info("terminate 判定: envId={} 已终止，幂等返回", lease.envId);
                return lease.snapshot();
            }
            case EXPIRED -> throw reject(RejectionReason.ILLEGAL_STATE_TRANSITION,
                    "环境已到期回收，不能再终止，envId=" + lease.envId);
            case FAILED -> {
                if (retriesExhausted(lease)) {
                    throw reject(RejectionReason.ILLEGAL_STATE_TRANSITION,
                            "环境已因重试耗尽失败终态，不能再终止，envId=" + lease.envId);
                }
            }
            default -> { /* READY / PENDING / PROVISIONING 允许终止 */ }
        }
        releaseAndFinish(lease, EnvironmentStatus.TERMINATED,
                "由 " + command.operator() + " 主动终止于 " + clock.now());
        return lease.snapshot();
    }

    // ============================ 失败重试 ============================

    /**
     * 对 FAILED 且未耗尽重试次数的环境重新供给。重试时重新检查并占用配额，
     * 重试失败立即释放配额，达到上限后为失败终态，绝不静默视为成功。
     */
    public synchronized LeaseView retry(Commands.RetryCommand command) {
        Lease lease = requireLease(command == null ? null : command.envId());
        requireOwner(lease, command.operator());
        log.info("retry 输入: envId={}, operator={}, status={}, attempts={}, maxAttempts={}",
                lease.envId, command.operator(), lease.status, lease.attempts(),
                properties.getProvision().getMaxAttempts());

        if (lease.status != EnvironmentStatus.FAILED) {
            throw reject(RejectionReason.ILLEGAL_STATE_TRANSITION,
                    "仅供给失败(FAILED)的环境可重试，当前状态=" + lease.status + "，envId=" + lease.envId);
        }
        if (retriesExhausted(lease)) {
            throw reject(RejectionReason.RETRY_LIMIT_EXHAUSTED,
                    "已达到供给重试上限(" + properties.getProvision().getMaxAttempts()
                            + ")，不得再重试，envId=" + lease.envId);
        }

        checkQuota(lease.owner, lease.team);
        provision(lease);
        return lease.snapshot();
    }

    // ============================ 查询与回收 ============================

    public synchronized LeaseView get(String envId) {
        return requireLease(envId).snapshot();
    }

    public synchronized LeaseView getByRequestId(String requestId) {
        String id = requireText(requestId, "requestId");
        Lease lease = leases.get(requestIndex.get(id));
        if (lease == null) {
            throw reject(RejectionReason.IDEMPOTENCY_KEY_NOT_FOUND, "requestId=" + id + " 不存在");
        }
        return lease.snapshot();
    }

    public synchronized List<LeaseView> list(String owner, String team) {
        return leases.values().stream()
                .filter(l -> owner == null || owner.isBlank() || l.owner.equals(owner.trim()))
                .filter(l -> team == null || team.isBlank() || l.team.equals(team.trim()))
                .sorted(Comparator.comparing(l -> l.createdAt))
                .map(Lease::snapshot)
                .toList();
    }

    /** 扫描全部 READY 环境，回收所有已到期租约；返回被回收数量。 */
    public synchronized int sweepExpired() {
        Instant now = clock.now();
        int count = 0;
        for (Lease lease : leases.values()) {
            if (lease.status == EnvironmentStatus.READY && isExpiredAt(lease, now)) {
                expireLease(lease, "后台扫描于 " + now + " 判定到期（expiresAt=" + lease.expiresAt + "）");
                count++;
            }
        }
        log.info("sweep 判定: now={}, 回收数量={}", now, count);
        return count;
    }

    public synchronized QuotaView ownerQuota(String owner) {
        String name = requireText(owner, "owner");
        return new QuotaView("OWNER", name, countUsed(l -> l.owner.equals(name)),
                properties.getDefaults().getOwnerQuota());
    }

    public synchronized QuotaView teamQuota(String team) {
        String name = requireText(team, "team");
        return new QuotaView("TEAM", name, countUsed(l -> l.team.equals(name)),
                properties.getDefaults().getTeamQuota());
    }

    // ============================ 内部实现 ============================

    /** 执行一次供给尝试并据此推进状态；成功则 READY，失败则保留记录并释放配额。 */
    private void provision(Lease lease) {
        int attempt = lease.attempts() + 1;
        lease.status = EnvironmentStatus.PROVISIONING;
        log.info("provision 输入: envId={}, attempt={}", lease.envId, attempt);

        ProvisionOutcome outcome = provisioner.provision(lease.envId, attempt);
        if (outcome instanceof ProvisionOutcome.Success success) {
            lease.resourceId = success.resourceId();
            lease.status = EnvironmentStatus.READY;
            lease.readyAt = clock.now();
            lease.expiresAt = lease.readyAt.plusSeconds(properties.getLease().getTtlSeconds());
            log.info("provision 判定: envId={} 第{}次供给成功 resourceId={}, expiresAt={}",
                    lease.envId, attempt, success.resourceId(), lease.expiresAt);
        } else if (outcome instanceof ProvisionOutcome.Failure failure) {
            lease.failures.add(new FailureRecord(attempt, clock.now(), failure.reasonCode(), failure.detail()));
            lease.status = EnvironmentStatus.FAILED;
            lease.terminalMessage = "第 " + attempt + " 次供给失败: " + failure.reasonCode() + " - " + failure.detail();
            if (retriesExhausted(lease)) {
                lease.terminalMessage += "；已达重试上限("
                        + properties.getProvision().getMaxAttempts() + ")，失败终态";
            }
            log.warn("provision 判定: envId={} 第{}次供给失败 reason={}, 耗尽={}，不占用配额",
                    lease.envId, attempt, failure.reasonCode(), retriesExhausted(lease));
        } else {
            throw new IllegalStateException("未知供给结果类型: " + outcome);
        }
    }

    /** 到期回收：仅 READY 可进入 EXPIRED；幂等且只释放一次资源。 */
    private void expireLease(Lease lease, String message) {
        if (lease.status != EnvironmentStatus.READY) {
            return;
        }
        releaseAndFinish(lease, EnvironmentStatus.EXPIRED, message);
    }

    /**
     * 释放资源并进入终态。lease.released 守卫保证与续租/重复终止并发时资源只释放一次，
     * 配额实时按状态聚合，因此不会出现配额被重复释放的半更新状态。
     */
    private void releaseAndFinish(Lease lease, EnvironmentStatus terminal, String message) {
        String resourceId = lease.resourceId;
        lease.resourceId = null;
        lease.status = terminal;
        lease.expiresAt = null;
        lease.terminalMessage = message;
        if (resourceId != null && !lease.released) {
            lease.released = true;
            provisioner.release(resourceId);
            log.info("release 判定: envId={} 释放 resourceId={}，进入 {}", lease.envId, resourceId, terminal);
        } else {
            log.info("release 判定: envId={} 无待释放资源，进入 {}", lease.envId, terminal);
        }
    }

    /** 读取路径上的惰性到期判定：已回收的环境状态不变，更不会被延长。 */
    private Lease touch(Lease lease) {
        if (lease.status == EnvironmentStatus.READY && isExpired(lease)) {
            expireLease(lease, "访问时判定到期（expiresAt=" + lease.expiresAt + "，now=" + clock.now() + "）");
        }
        return lease;
    }

    private boolean isExpired(Lease lease) {
        return isExpiredAt(lease, clock.now());
    }

    private boolean isExpiredAt(Lease lease, Instant now) {
        return lease.expiresAt != null && !now.isBefore(lease.expiresAt);
    }

    private boolean retriesExhausted(Lease lease) {
        return lease.attempts() >= properties.getProvision().getMaxAttempts();
    }

    private void checkQuota(String owner, String team) {
        int ownerUsed = countUsed(l -> l.owner.equals(owner));
        int ownerLimit = properties.getDefaults().getOwnerQuota();
        log.info("quota 判定输入: owner={}, ownerUsed={}/{}", owner, ownerUsed, ownerLimit);
        if (ownerUsed >= ownerLimit) {
            throw reject(RejectionReason.OWNER_QUOTA_EXCEEDED,
                    "所有者配额已满: " + owner + " " + ownerUsed + "/" + ownerLimit);
        }
        int teamUsed = countUsed(l -> l.team.equals(team));
        int teamLimit = properties.getDefaults().getTeamQuota();
        log.info("quota 判定输入: team={}, teamUsed={}/{}", team, teamUsed, teamLimit);
        if (teamUsed >= teamLimit) {
            throw reject(RejectionReason.TEAM_QUOTA_EXCEEDED,
                    "团队配额已满: " + team + " " + teamUsed + "/" + teamLimit);
        }
    }

    /** 统计当前仍占用配额的环境数：仅非终态且持有/占位资源的状态。FAILED 即失败即释放，不占用。 */
    private int countUsed(java.util.function.Predicate<Lease> scope) {
        int used = 0;
        for (Lease l : leases.values()) {
            if (scope.test(l) && occupiesQuota(l)) {
                used++;
            }
        }
        return used;
    }

    private boolean occupiesQuota(Lease l) {
        return switch (l.status) {
            case PENDING, PROVISIONING, READY -> true;
            // FAILED 在本实现中失败即刻释放配额，故不占用；终态不占用
            case FAILED, EXPIRED, TERMINATED -> false;
        };
    }

    private Lease requireLease(String envId) {
        String id = requireText(envId, "envId");
        Lease lease = leases.get(id);
        if (lease == null) {
            throw reject(RejectionReason.ENV_NOT_FOUND, "环境不存在: " + id);
        }
        return touch(lease);
    }

    private void requireOwner(Lease lease, String operator) {
        String op = requireText(operator, "operator");
        if (!lease.owner.equals(op)) {
            throw reject(RejectionReason.NOT_OWNER,
                    "跨所有者操作被拒绝: envId=" + lease.envId + " 属于 " + lease.owner
                            + "，操作者=" + op);
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw reject(RejectionReason.INVALID_REQUEST, field + " 不能为空");
        }
        return value.trim();
    }

    private OrchestrationException reject(RejectionReason reason, String message) {
        log.warn("reject 判定: reason={}, {}", reason, message);
        return new OrchestrationException(reason, message);
    }

    /** 租约聚合：可变状态仅供编排服务在锁内访问，对外只透出不可变快照。 */
    private static final class Lease {
        private final String envId;
        private final String requestId;
        private final String owner;
        private final String team;
        private final Instant createdAt;
        private final List<FailureRecord> failures = new ArrayList<>();

        private EnvironmentStatus status = EnvironmentStatus.PENDING;
        private Instant readyAt;
        private Instant expiresAt;
        private int renewedCount;
        private String resourceId;
        private String terminalMessage;
        private boolean released;

        private Lease(String envId, String requestId, String owner, String team, Instant createdAt) {
            this.envId = envId;
            this.requestId = requestId;
            this.owner = owner;
            this.team = team;
            this.createdAt = createdAt;
        }

        private int attempts() {
            return failures.size();
        }

        private LeaseView snapshot() {
            return new LeaseView(envId, requestId, owner, team, status, createdAt, readyAt,
                    expiresAt, renewedCount, resourceId, List.copyOf(failures), terminalMessage);
        }
    }
}
