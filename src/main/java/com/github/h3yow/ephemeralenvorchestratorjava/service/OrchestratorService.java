package com.github.h3yow.ephemeralenvorchestratorjava.service;

import com.github.h3yow.ephemeralenvorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.FailureCode;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.FailureRecord;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.Lease;
import com.github.h3yow.ephemeralenvorchestratorjava.domain.LeaseStatus;
import com.github.h3yow.ephemeralenvorchestratorjava.error.BusinessException;
import com.github.h3yow.ephemeralenvorchestratorjava.error.ErrorCode;
import com.github.h3yow.ephemeralenvorchestratorjava.lease.LeaseRepository;
import com.github.h3yow.ephemeralenvorchestratorjava.provision.ProvisionResult;
import com.github.h3yow.ephemeralenvorchestratorjava.provision.Provisioner;
import com.github.h3yow.ephemeralenvorchestratorjava.quota.QuotaLedger;
import com.github.h3yow.ephemeralenvorchestratorjava.quota.QuotaView;
import com.github.h3yow.ephemeralenvorchestratorjava.time.TimeSource;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.ApplyLeaseRequest;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.LeaseView;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.RenewRequest;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.TransferRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 租约编排核心服务。
 *
 * <p>并发模型：单一公平 {@link ReentrantLock} 串行化所有“检查 + 状态/配额写入”的临界区，
 * 供给等外部动作移到锁外执行（按 attempt 快照），完成后再次入锁裁决。这样：
 * <ul>
 *   <li>配额检查与计数同临界区完成，绝不超卖；</li>
 *   <li>requestId 幂等登记在临界区内完成，重复提交复用同一租约，不重复分配资源；</li>
 *   <li>续租与过期回收的“是否已过期”判定与状态写入不可分割，只有一方胜出，
 *       不会出现已结束环境仍被续租或配额被重复释放；</li>
 *   <li>供给期间被终止时，完成阶段会识别终态并回收已分配槽位，杜绝资源泄漏。</li>
 * </ul>
 *
 * <p>时钟安全：所有时间敏感写操作经 {@link #currentTimeForMutation()} 取时间，
 * 维护单调高水位；一旦发现时间回拨立即拒绝（CLOCK_ROLLBACK_DETECTED），
 * 防止提前回收有效租约或延长已结束租约。</p>
 */
@Service
public class OrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);

    private final LeaseRepository repository;
    private final QuotaLedger ledger;
    private final Provisioner provisioner;
    private final TimeSource timeSource;
    private final OrchestratorProperties properties;

    /** 全局状态锁：保护租约状态、配额账本、时钟高水位的联合一致性（公平锁减少饥饿）。 */
    private final ReentrantLock lock = new ReentrantLock(true);

    /** 已观测到的最大时间，用于检测系统时间回拨；仅在持锁的写路径更新。 */
    private Instant highWaterMark;

    /**
     * 转让操作的幂等登记表：requestId -> 已生效转让记录（含目标归属与转让后视图快照）。
     * 仅在全局锁内访问。同键重复提交直接复用快照；同键但环境或目标不同则冲突拒绝。
     */
    private final java.util.Map<String, TransferRecord> transferRequests = new java.util.HashMap<>();

    /** 一次已生效转让的不可变登记。 */
    private record TransferRecord(String leaseId, String targetOwner, String targetTeam,
                                  LeaseView view) {
    }

    public OrchestratorService(LeaseRepository repository, QuotaLedger ledger,
                               Provisioner provisioner, TimeSource timeSource,
                               OrchestratorProperties properties) {
        this.repository = repository;
        this.ledger = ledger;
        this.provisioner = provisioner;
        this.timeSource = timeSource;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ 申请

    /**
     * 申请环境（幂等）。同一 requestId 的重复提交（含并发）返回同一个租约，绝不重复分配。
     */
    public LeaseView apply(ApplyLeaseRequest request) {
        Duration ttl = resolveTtl(request.getTtl());
        Lease lease;
        lock.lock();
        try {
            Instant now = currentTimeForMutation();
            Lease existing = repository.findByRequestId(request.getRequestId()).orElse(null);
            if (existing != null) {
                log.info("申请判定[幂等命中] 输入 requestId={} owner={} team={} ttl={}；"
                                + "依据：已存在租约 leaseId={} status={}，复用而不重新分配",
                        request.getRequestId(), request.getOwner(), request.getTeam(), ttl,
                        existing.getId(), existing.getStatus());
                return LeaseView.from(existing);
            }
            enforceQuota(request.getOwner(), request.getTeam());

            lease = new Lease(UUID.randomUUID().toString(), request.getRequestId(),
                    request.getOwner(), request.getTeam(), now, ttl,
                    properties.getMaxProvisionAttempts());
            // enforceQuota 已在同一临界区确认未满，此处显式占用 owner+team 各一个配额；
            // 绝不能再调用会“检查并计数”的 tryAcquire，否则单个租约会被重复计数。
            ledger.acquire(request.getOwner(), request.getTeam());
            lease.acquireQuota();
            lease.markProvisioning(now);
            repository.save(lease);
            // 全局锁内此前已确认该 requestId 不存在，登记必然成功。
            repository.registerRequest(request.getRequestId(), lease.getId());
            log.info("申请判定[受理] 输入 requestId={} owner={} team={} ttl={}；"
                            + "依据：owner 配额 {}/{} team 配额 {}/{} 均未满，leaseId={} 已占配额进入供给",
                    request.getRequestId(), request.getOwner(), request.getTeam(), ttl,
                    ledger.ownerView(request.getOwner()).used(),
                    ledger.ownerView(request.getOwner()).limit(),
                    ledger.teamView(request.getTeam()).used(),
                    ledger.teamView(request.getTeam()).limit(), lease.getId());
        } finally {
            lock.unlock();
        }
        return provisionAndComplete(lease, ttl);
    }

    // ------------------------------------------------------------------ 查询

    public LeaseView get(String leaseId) {
        String slotToRelease = null;
        LeaseView view;
        lock.lock();
        try {
            Lease lease = requireLease(leaseId);
            if (lease.getStatus() == LeaseStatus.READY && lease.isExpiredAt(timeSource.now())) {
                expireLease(lease, timeSource.now(), "查询时惰性回收");
                slotToRelease = lease.getResourceSlot();
                repository.save(lease);
            }
            view = LeaseView.from(lease);
        } finally {
            lock.unlock();
        }
        // 供给器交互放在锁外，避免外部实现阻塞全局临界区
        if (slotToRelease != null) {
            provisioner.release(slotToRelease);
        }
        return view;
    }

    public LeaseView getByRequestId(String requestId) {
        String slotToRelease = null;
        LeaseView view;
        lock.lock();
        try {
            Lease lease = repository.findByRequestId(requestId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.LEASE_NOT_FOUND,
                            "找不到 requestId=" + requestId + " 对应的租约"));
            if (lease.getStatus() == LeaseStatus.READY && lease.isExpiredAt(timeSource.now())) {
                expireLease(lease, timeSource.now(), "按 requestId 查询时惰性回收");
                slotToRelease = lease.getResourceSlot();
                repository.save(lease);
            }
            view = LeaseView.from(lease);
        } finally {
            lock.unlock();
        }
        if (slotToRelease != null) {
            provisioner.release(slotToRelease);
        }
        return view;
    }

    // ------------------------------------------------------------------ 终止

    public LeaseView terminate(String leaseId, String operator) {
        String slotToRelease;
        lock.lock();
        try {
            Instant now = currentTimeForMutation();
            Lease lease = requireLease(leaseId);
            requireOwner(lease, operator);
            switch (lease.getStatus()) {
                case TERMINATED -> throw new BusinessException(ErrorCode.LEASE_ALREADY_TERMINATED,
                        "租约 " + leaseId + " 已终止，不能重复终止");
                case EXPIRED -> throw new BusinessException(ErrorCode.LEASE_ALREADY_EXPIRED,
                        "租约 " + leaseId + " 已过期回收，不能终止");
                default -> { /* READY/PENDING/PROVISIONING/FAILED 允许终止 */ }
            }
            boolean changed = lease.markTerminated(now);
            slotToRelease = lease.getResourceSlot();
            if (changed && lease.releaseQuota()) {
                ledger.release(lease.getOwner(), lease.getTeam());
                log.info("终止判定[释放配额] leaseId={} operator={}，释放后 owner={}/{} team={}/{}",
                        leaseId, operator,
                        ledger.ownerView(lease.getOwner()).used(), ledger.ownerView(lease.getOwner()).limit(),
                        ledger.teamView(lease.getTeam()).used(), ledger.teamView(lease.getTeam()).limit());
            }
            repository.save(lease);
            log.info("终止判定[成功] 输入 leaseId={} operator={}；依据：归属匹配且原状态允许终止 -> TERMINATED",
                    leaseId, operator);
        } finally {
            lock.unlock();
        }
        if (slotToRelease != null) {
            provisioner.release(slotToRelease);
        }
        return get(leaseId);
    }

    // ------------------------------------------------------------------ 续租

    public LeaseView renew(String leaseId, RenewRequest request) {
        Duration extension = resolveExtension(request.getExtension());
        String slotToRelease = null;
        BusinessException deferred = null;
        LeaseView view = null;
        lock.lock();
        try {
            Instant now = currentTimeForMutation();
            Lease lease = requireLease(leaseId);
            requireOwner(lease, operatorOf(request));
            if (lease.getStatus() == LeaseStatus.TERMINATED) {
                throw new BusinessException(ErrorCode.LEASE_ALREADY_TERMINATED,
                        "租约 " + leaseId + " 已终止，不能续租");
            }
            if (lease.getStatus() == LeaseStatus.EXPIRED) {
                throw new BusinessException(ErrorCode.LEASE_ALREADY_EXPIRED,
                        "租约 " + leaseId + " 已过期回收，不能续租");
            }
            if (lease.getStatus() != LeaseStatus.READY) {
                throw new BusinessException(ErrorCode.LEASE_NOT_READY,
                        "租约 " + leaseId + " 当前状态=" + lease.getStatus() + "，仅 READY 可续租");
            }
            // 关键竞态裁决：过期判定与回收在同一临界区原子完成，续租必败且原因明确。
            if (lease.isExpiredAt(now)) {
                expireLease(lease, now, "续租时发现已到期");
                repository.save(lease);
                slotToRelease = lease.getResourceSlot();
                deferred = new BusinessException(ErrorCode.LEASE_ALREADY_EXPIRED,
                        "租约 " + leaseId + " 在 now=" + now + " 已到期（expiresAt="
                                + lease.getExpiresAt() + "），已原子回收，不能续租");
            } else {
                Instant newExpiry = lease.extend(now, extension);
                repository.save(lease);
                log.info("续租判定[成功] 输入 leaseId={} operator={} extension={}；依据：now={} < expiresAt，"
                                + "在原到期时间上延长，新到期时间={}",
                        leaseId, operatorOf(request), extension, now, newExpiry);
                view = LeaseView.from(lease);
            }
        } finally {
            lock.unlock();
        }
        // 锁外回收供给资源（续租触发的过期路径同样不能泄漏资源槽）
        if (slotToRelease != null) {
            provisioner.release(slotToRelease);
        }
        if (deferred != null) {
            throw deferred;
        }
        return view;
    }

    // ------------------------------------------------------------------ 转让

    /**
     * 租约转让（幂等）：把一条 READY 且未到期的环境原地转给新的 owner/team，
     * 不重新供给、不换实例。目标配额不足/状态不允许/非当前归属者/已结束等拒绝原因相互可区分。
     *
     * <p>全部判定与“源释放 + 目标占用 + 归属改写 + 幂等登记”在同一全局临界区内一次完成，
     * 因此要么整体生效、要么两边都不动；源与目标在某一维度相同时跳过该维度，绝不重复计数。
     */
    public LeaseView transfer(String leaseId, TransferRequest request) {
        String slotToRelease = null;
        BusinessException deferred = null;
        LeaseView result = null;
        lock.lock();
        try {
            Instant now = currentTimeForMutation();

            // 1) 幂等判定先行：同键并发也由全局锁串行，只有第一个请求能真正执行。
            TransferRecord record = transferRequests.get(request.getRequestId());
            if (record != null) {
                return replayOrConflict(record, leaseId, request);
            }

            // 2) 发起人必须是当前归属者（NOT_OWNER 与状态/配额原因相互可区分）。
            Lease lease = requireLease(leaseId);
            requireOwner(lease, request.getOperator());

            // 3) 状态裁决：只有 READY 且未到期可转让；终态/其它状态原因各自明确；
            //    发现恰好在此刻到期则原子回收，结论与续租入口完全一致（LEASE_ALREADY_EXPIRED）。
            switch (lease.getStatus()) {
                case TERMINATED -> throw new BusinessException(ErrorCode.LEASE_ALREADY_TERMINATED,
                        "租约 " + leaseId + " 已终止，不能转让");
                case EXPIRED -> throw new BusinessException(ErrorCode.LEASE_ALREADY_EXPIRED,
                        "租约 " + leaseId + " 已过期回收，不能转让");
                case READY -> {
                    if (lease.isExpiredAt(now)) {
                        expireLease(lease, now, "转让时发现已到期");
                        repository.save(lease);
                        slotToRelease = lease.getResourceSlot();
                        // 延迟到锁外释放资源槽之后再抛出（与续租的过期路径同一模式，杜绝槽位泄漏）
                        deferred = new BusinessException(ErrorCode.LEASE_ALREADY_EXPIRED,
                                "租约 " + leaseId + " 在 now=" + now + " 已到期（expiresAt="
                                        + lease.getExpiresAt() + "），已原子回收，不能转让");
                    }
                }
                default -> throw new BusinessException(ErrorCode.LEASE_NOT_TRANSFERABLE,
                        "租约 " + leaseId + " 当前状态=" + lease.getStatus()
                                + "，仅 READY 且未到期的环境可以转让");
            }
            if (deferred != null) {
                // 已原子回收：跳过后续归属与配额逻辑，锁外释放资源槽后再抛出明确拒绝。
            } else {
                result = transferUnderLock(leaseId, request, lease, now);
            }
        } finally {
            lock.unlock();
        }
        // 转让本身不换资源槽；唯一的锁外动作是转让判定中内联到期回收产生的槽位释放（发生在拒绝路径）。
        if (slotToRelease != null) {
            provisioner.release(slotToRelease);
        }
        if (deferred != null) {
            throw deferred;
        }
        return result;
    }

    /** 持锁调用：在通过归属/状态/到期裁决后，完成目标归一化、配额检查与原子搬运、归属改写及幂等登记。 */
    private LeaseView transferUnderLock(String leaseId, TransferRequest request, Lease lease, Instant now) {
        // 归一化目标归属：未指定的维度保持原值；两维都不变属于空操作，明确拒绝。
        String currentOwner = lease.getOwner();
        String currentTeam = lease.getTeam();
        String targetOwner = normalizeTarget(request.getTargetOwner(), currentOwner);
        String targetTeam = normalizeTarget(request.getTargetTeam(), currentTeam);
        if (targetOwner.equals(currentOwner) && targetTeam.equals(currentTeam)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "转让目标与当前归属完全相同（owner=" + currentOwner + " team=" + currentTeam
                            + "），空转让不予受理");
        }

        // 目标配额检查先于任何账本写入（owner 优先、team 其次），任一不足整体拒绝、两边不动。
        if (!targetOwner.equals(currentOwner)) {
            QuotaView targetOwnerView = ledger.ownerView(targetOwner);
            if (!targetOwnerView.isUnlimited()
                    && targetOwnerView.used() >= targetOwnerView.limit()) {
                throw new BusinessException(ErrorCode.OWNER_QUOTA_EXCEEDED,
                        "目标所有者配额不足，转让被拒绝：targetOwner=" + targetOwner
                                + " 已用=" + targetOwnerView.used()
                                + " 上限=" + targetOwnerView.limit());
            }
        }
        if (!targetTeam.equals(currentTeam)) {
            QuotaView targetTeamView = ledger.teamView(targetTeam);
            if (!targetTeamView.isUnlimited()
                    && targetTeamView.used() >= targetTeamView.limit()) {
                throw new BusinessException(ErrorCode.TEAM_QUOTA_EXCEEDED,
                        "目标团队配额不足，转让被拒绝：targetTeam=" + targetTeam
                                + " 已用=" + targetTeamView.used()
                                + " 上限=" + targetTeamView.limit());
            }
        }

        // 原子搬运：源释放与目标占用按维度进行，相同维度跳过，杜绝半更新/重复计数。
        if (!targetOwner.equals(currentOwner)) {
            ledger.releaseOwner(currentOwner);
            ledger.acquireOwner(targetOwner);
        }
        if (!targetTeam.equals(currentTeam)) {
            ledger.releaseTeam(currentTeam);
            ledger.acquireTeam(targetTeam);
        }
        lease.transferOwnership(targetOwner, targetTeam, now);
        repository.save(lease);
        LeaseView view = LeaseView.from(lease);
        // 登记放在所有写入之后：只有真正生效的转让才能被幂等重放，被拒请求不占用键。
        transferRequests.put(request.getRequestId(),
                new TransferRecord(leaseId, targetOwner, targetTeam, view));
        log.info("转让判定[成功] 输入 leaseId={} operator={} {}|{} -> {}|{} requestId={}；"
                        + "依据：READY 未到期且目标配额未满，配额原子搬运后 owner 水位 {}/{}、team 水位 {}/{}",
                leaseId, request.getOperator(), currentOwner, currentTeam, targetOwner, targetTeam,
                request.getRequestId(),
                ledger.ownerView(targetOwner).used(), ledger.ownerView(targetOwner).limit(),
                ledger.teamView(targetTeam).used(), ledger.teamView(targetTeam).limit());
        return view;
    }

    /**
     * 持锁调用：幂等重放一致结果；同键复用于不同环境或不同目标则明确冲突并说明既往用途。
     * 重复提交时留空的目标维度语义为“保持当前归属”，转让生效后当前归属即既往目标，故按登记值归一化。
     */
    private LeaseView replayOrConflict(TransferRecord record, String leaseId, TransferRequest request) {
        String targetOwner = normalizeTarget(request.getTargetOwner(), record.targetOwner());
        String targetTeam = normalizeTarget(request.getTargetTeam(), record.targetTeam());
        boolean sameLease = record.leaseId().equals(leaseId);
        boolean sameOwner = record.targetOwner().equals(targetOwner);
        boolean sameTeam = record.targetTeam().equals(targetTeam);
        if (sameLease && sameOwner && sameTeam) {
            log.info("转让判定[幂等命中] requestId={} leaseId={} -> {}|{}，复用唯一一次生效结果",
                    request.getRequestId(), leaseId, record.targetOwner(), record.targetTeam());
            return record.view();
        }
        throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "requestId=" + request.getRequestId() + " 已用于另一次转让：leaseId="
                        + record.leaseId() + " 目标 owner=" + record.targetOwner()
                        + " team=" + record.targetTeam() + "；本次请求 leaseId=" + leaseId
                        + " 目标 owner=" + targetOwner + " team=" + targetTeam
                        + "，键复用冲突，拒绝按既往结果糊弄");
    }

    /** 目标归属归一化：空白表示该维度保持不变，取调用方给定的当前值；非空值去空白后原样使用。 */
    private static String normalizeTarget(String value, String current) {
        if (value == null || value.isBlank()) {
            return current;
        }
        return value.trim();
    }

    // ------------------------------------------------------------------ 重试

    public LeaseView retry(String leaseId, String operator) {
        Lease lease;
        lock.lock();
        try {
            Instant now = currentTimeForMutation();
            lease = requireLease(leaseId);
            requireOwner(lease, operator);
            switch (lease.getStatus()) {
                case FAILED -> {
                    if (!lease.canRetry()) {
                        FailureRecord last = lease.getFailures().isEmpty() ? null
                                : lease.getFailures().get(lease.getFailures().size() - 1);
                        throw new BusinessException(ErrorCode.RETRY_LIMIT_EXCEEDED,
                                "租约 " + leaseId + " 已达供给尝试上限 " + lease.getMaxAttempts()
                                        + "，最后失败=" + (last == null ? "无" : last.code() + ":" + last.message())
                                        + "，FAILED 为终态，不会静默视为成功");
                    }
                }
                case TERMINATED -> throw new BusinessException(ErrorCode.LEASE_ALREADY_TERMINATED,
                        "租约 " + leaseId + " 已终止，不能重试");
                case EXPIRED -> throw new BusinessException(ErrorCode.LEASE_ALREADY_EXPIRED,
                        "租约 " + leaseId + " 已过期回收，不能重试");
                case READY -> throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "租约 " + leaseId + " 已就绪，无需重试");
                default -> throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "租约 " + leaseId + " 当前状态=" + lease.getStatus() + "，供给进行中或尚未失败，不能重试");
            }
            lease.markProvisioning(now);
            repository.save(lease);
            log.info("重试判定[受理] 输入 leaseId={} operator={}；依据：FAILED 且 attempt={}/{} 未达上限",
                    leaseId, operator, lease.getAttemptCount(), lease.getMaxAttempts());
        } finally {
            lock.unlock();
        }
        return provisionAndComplete(lease, lease.getTtl());
    }

    // ------------------------------------------------------------ 过期回收

    /**
     * 扫描并原子回收已到期租约。时间回拨时跳过本轮（绝不提前回收）。
     *
     * @return 本轮回收数量
     */
    public int reapExpired() {
        List<String> slots = new ArrayList<>();
        int count;
        lock.lock();
        try {
            Instant now;
            try {
                now = currentTimeForMutation();
            } catch (BusinessException ex) {
                if (ex.getCode() == ErrorCode.CLOCK_ROLLBACK_DETECTED) {
                    log.warn("过期回收跳过：{}", ex.getMessage());
                    return 0;
                }
                throw ex;
            }
            int innerCount = 0;
            for (Lease lease : repository.findAll()) {
                if (lease.getStatus() == LeaseStatus.READY && lease.isExpiredAt(now)) {
                    expireLease(lease, now, "定时回收扫描命中");
                    if (lease.getResourceSlot() != null) {
                        slots.add(lease.getResourceSlot());
                    }
                    repository.save(lease);
                    innerCount++;
                }
            }
            count = innerCount;
            log.info("过期回收完成：now={} 回收数量={}", now, count);
        } finally {
            lock.unlock();
        }
        // 供给器回收放在锁外执行
        for (String slot : slots) {
            provisioner.release(slot);
        }
        return count;
    }

    // ------------------------------------------------------------------ 配额

    public QuotaView ownerQuota(String owner) {
        lock.lock();
        try {
            return ledger.ownerView(owner);
        } finally {
            lock.unlock();
        }
    }

    public QuotaView teamQuota(String team) {
        lock.lock();
        try {
            return ledger.teamView(team);
        } finally {
            lock.unlock();
        }
    }

    // -------------------------------------------------------------- 内部方法

    /**
     * 锁外执行供给，完成后再入锁裁决。attempt 序号在入锁时快照；
     * 完成时若租约已被终止（并发），成功分配的槽位会被立即回收，不留泄漏。
     */
    private LeaseView provisionAndComplete(Lease lease, Duration ttl) {
        int attemptNo;
        lock.lock();
        try {
            attemptNo = lease.getAttemptCount() + 1;
        } finally {
            lock.unlock();
        }

        ProvisionResult result;
        try {
            result = provisioner.provision(lease, attemptNo);
        } catch (RuntimeException ex) {
            log.warn("供给器抛出异常 leaseId={} attempt={}", lease.getId(), attemptNo, ex);
            result = ProvisionResult.failure("供给器异常: " + ex.getClass().getSimpleName()
                    + ":" + ex.getMessage());
        }

        String slotToRelease = null;
        LeaseView finalView;
        lock.lock();
        try {
            Lease current = repository.findById(lease.getId()).orElseThrow();
            if (current.getStatus() != LeaseStatus.PROVISIONING) {
                // 并发裁决：供给期间租约已被终止/其它终态化。成功拿到的槽位必须回收，
                // 配额已由终止路径处理，这里不得二次释放。
                if (result.success()) {
                    slotToRelease = result.resourceSlot();
                }
                log.info("供给完成裁决[已终态] leaseId={} status={} success={}；依据：终态优先，丢弃本次供给结果",
                        lease.getId(), current.getStatus(), result.success());
                finalView = LeaseView.from(current);
            } else {
                finalView = completeProvisioningUnderLock(current, result, ttl, attemptNo);
            }
        } finally {
            lock.unlock();
        }
        if (slotToRelease != null) {
            provisioner.release(slotToRelease);
        }
        return finalView;
    }

    /** 持锁调用：把一次供给结果落到租约上。 */
    private LeaseView completeProvisioningUnderLock(Lease lease, ProvisionResult result,
                                                    Duration ttl, int attemptNo) {
        Instant now;
        try {
            now = currentTimeForMutation();
        } catch (BusinessException ex) {
            if (ex.getCode() != ErrorCode.CLOCK_ROLLBACK_DETECTED) {
                throw ex;
            }
            // 时钟回拨：不得用回拨时间计算到期时刻。成功分配的槽位立即回收，
            // 以单调高水位记录一次可解释失败，按重试规则决定是否释放配额。
            if (result.success()) {
                provisioner.release(result.resourceSlot());
            }
            Instant safeTs = highWaterMark;
            lease.recordAttempt(FailureCode.PROVISIONER_ERROR,
                    "检测到时钟回拨，已回滚本次供给资源：" + ex.getMessage(), safeTs);
            releaseQuotaIfRetriesExhausted(lease);
            repository.save(lease);
            log.warn("供给完成裁决[时钟回拨] leaseId={} attempt={} 已回滚资源并记录失败",
                    lease.getId(), attemptNo);
            return LeaseView.from(lease);
        }

        if (result.success()) {
            lease.markReady(result.endpoint(), result.resourceSlot(), now.plus(ttl), now);
            repository.save(lease);
            log.info("供给完成裁决[就绪] leaseId={} attempt={} endpoint={} expiresAt={}",
                    lease.getId(), attemptNo, result.endpoint(), lease.getExpiresAt());
        } else {
            FailureCode code = result.resourceExhausted()
                    ? FailureCode.RESOURCE_POOL_EXHAUSTED
                    : FailureCode.PROVISIONER_TRANSIENT;
            lease.recordAttempt(code, result.message(), now);
            boolean exhausted = !lease.canRetry();
            releaseQuotaIfRetriesExhausted(lease);
            repository.save(lease);
            log.warn("供给完成裁决[失败] leaseId={} attempt={} code={} message={} 达上限={}（{}）",
                    lease.getId(), attemptNo, code, result.message(), exhausted,
                    exhausted ? "FAILED 终态并已释放配额" : "FAILED 可重试，继续占用配额");
        }
        return LeaseView.from(lease);
    }

    /** 达到重试上限：释放配额（幂等），失败不长期占用配额。 */
    private void releaseQuotaIfRetriesExhausted(Lease lease) {
        if (!lease.canRetry() && lease.releaseQuota()) {
            ledger.release(lease.getOwner(), lease.getTeam());
            log.info("配额释放[重试上限] leaseId={} owner={} team={}",
                    lease.getId(), lease.getOwner(), lease.getTeam());
        }
    }

    /** 持锁调用：原子地把 READY 到期租约置 EXPIRED 并释放配额。 */
    private void expireLease(Lease lease, Instant now, String reason) {
        lease.markExpired(now);
        if (lease.releaseQuota()) {
            ledger.release(lease.getOwner(), lease.getTeam());
        }
        log.info("过期回收 leaseId={} owner={} reason={} now={} expiresAt={}，配额已释放={}",
                lease.getId(), lease.getOwner(), reason, now, lease.getExpiresAt(),
                !lease.isQuotaHeld());
    }

    /** 读路径上的惰性过期逻辑已内联到 get/getByRequestId（需在解锁后释放供给资源）。 */

    /** 持锁调用：先 owner 后 team 校验，拒绝原因可区分，且与检查在同一临界区。 */
    private void enforceQuota(String owner, String team) {
        QuotaView ownerView = ledger.ownerView(owner);
        if (!ownerView.isUnlimited() && ownerView.used() >= ownerView.limit()) {
            throw new BusinessException(ErrorCode.OWNER_QUOTA_EXCEEDED,
                    "所有者配额不足：owner=" + owner + " 已用=" + ownerView.used()
                            + " 上限=" + ownerView.limit());
        }
        QuotaView teamView = ledger.teamView(team);
        if (!teamView.isUnlimited() && teamView.used() >= teamView.limit()) {
            throw new BusinessException(ErrorCode.TEAM_QUOTA_EXCEEDED,
                    "团队配额不足：team=" + team + " 已用=" + teamView.used()
                            + " 上限=" + teamView.limit());
        }
    }

    /**
     * 时间敏感写操作的统一取时入口：拒绝时钟回拨，否则推进单调高水位。
     * 必须在持锁条件下调用。
     */
    private Instant currentTimeForMutation() {
        Instant now = timeSource.now();
        if (highWaterMark != null && now.isBefore(highWaterMark)) {
            throw new BusinessException(ErrorCode.CLOCK_ROLLBACK_DETECTED,
                    "检测到系统时间回拨：now=" + now + " 早于已观测时间 " + highWaterMark
                            + "，为避免提前回收有效租约或延长已结束租约，拒绝本次操作");
        }
        highWaterMark = now;
        return now;
    }

    private Lease requireLease(String leaseId) {
        return repository.findById(leaseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LEASE_NOT_FOUND,
                        "租约不存在：leaseId=" + leaseId));
    }

    private void requireOwner(Lease lease, String operator) {
        if (operator == null || operator.isBlank() || !lease.getOwner().equals(operator)) {
            throw new BusinessException(ErrorCode.NOT_OWNER,
                    "跨所有者操作被拒绝：leaseId=" + lease.getId() + " owner=" + lease.getOwner()
                            + " operator=" + operator);
        }
    }

    private Duration resolveTtl(String raw) {
        if (raw == null || raw.isBlank()) {
            return properties.getDefaultTtl();
        }
        Duration ttl;
        try {
            ttl = Duration.parse(raw);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "ttl 必须为 ISO-8601 时长（例如 PT10M），实际=" + raw);
        }
        if (ttl.isZero() || ttl.isNegative()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "ttl 必须为正时长，实际=" + raw);
        }
        if (ttl.compareTo(properties.getMinTtl()) < 0 || ttl.compareTo(properties.getMaxTtl()) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "ttl=" + ttl + " 超出允许区间 [" + properties.getMinTtl()
                            + "," + properties.getMaxTtl() + "]");
        }
        return ttl;
    }

    private Duration resolveExtension(String raw) {
        if (raw == null || raw.isBlank()) {
            return properties.getRenewalExtension();
        }
        try {
            Duration extension = Duration.parse(raw);
            if (extension.isZero() || extension.isNegative()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "续租时长必须为正，实际=" + raw);
            }
            return extension;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "extension 必须为 ISO-8601 时长（例如 PT5M），实际=" + raw);
        }
    }

    private static String operatorOf(RenewRequest request) {
        return request.getOperator();
    }
}
