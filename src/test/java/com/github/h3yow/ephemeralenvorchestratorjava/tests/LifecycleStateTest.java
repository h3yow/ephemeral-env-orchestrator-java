package com.github.h3yow.ephemeralenvorchestratorjava.tests;

import com.github.h3yow.ephemeralenvorchestratorjava.domain.LeaseStatus;
import com.github.h3yow.ephemeralenvorchestratorjava.error.BusinessException;
import com.github.h3yow.ephemeralenvorchestratorjava.error.ErrorCode;
import com.github.h3yow.ephemeralenvorchestratorjava.tests.support.TestHarness;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.ApplyLeaseRequest;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.LeaseView;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.OperationRequest;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.RenewRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 生命周期与非法流转测试：覆盖 申请->就绪->终止、非法流转拒绝原因可区分、归属校验。
 */
class LifecycleStateTest {

    private static final Logger log = LoggerFactory.getLogger(LifecycleStateTest.class);

    private TestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new TestHarness();
    }

    private ApplyLeaseRequest applyReq(String requestId, String owner, String team, String ttl) {
        ApplyLeaseRequest req = new ApplyLeaseRequest();
        req.setRequestId(requestId);
        req.setOwner(owner);
        req.setTeam(team);
        req.setTtl(ttl);
        return req;
    }

    private OperationRequest op(String operator) {
        OperationRequest r = new OperationRequest();
        r.setOperator(operator);
        return r;
    }

    private RenewRequest renew(String operator, String extension) {
        RenewRequest r = new RenewRequest();
        r.setOperator(operator);
        r.setExtension(extension);
        return r;
    }

    @Test
    @DisplayName("完整生命周期：申请即就绪，续租成功，终止后不可再续租/终止")
    void fullLifecycle() {
        log.info("===== 用例输入：申请 requestId=req-1 owner=alice team=payments ttl=PT10M =====");
        LeaseView lease = harness.service.apply(applyReq("req-1", "alice", "payments", "PT10M"));

        assertEquals(LeaseStatus.READY, lease.status(), "判定依据：模拟供给成功，应直接 READY");
        assertEquals(0, lease.failures().size(), "判定依据：无失败记录");
        assertNotNull(lease.endpoint());
        assertEquals(harness.clock.now().plus(java.time.Duration.ofMinutes(10)), lease.expiresAt());
        log.info("判定结果：leaseId={} status={} expiresAt={}", lease.id(), lease.status(), lease.expiresAt());

        log.info("===== 输入：续租 leaseId={} operator=alice extension=PT5M =====", lease.id());
        LeaseView renewed = harness.service.renew(lease.id(), renew("alice", "PT5M"));
        assertEquals(harness.clock.now().plus(java.time.Duration.ofMinutes(15)), renewed.expiresAt(),
                "判定依据：在原到期时间 00:10 上 +5m = 00:15");
        assertEquals(LeaseStatus.READY, renewed.status());

        log.info("===== 输入：终止 leaseId={} operator=alice =====", lease.id());
        LeaseView terminated = harness.service.terminate(lease.id(), "alice");
        assertEquals(LeaseStatus.TERMINATED, terminated.status());
        assertFalse(terminated.quotaHeld(), "判定依据：终止后配额必须释放");

        BusinessException exRenew = assertThrows(BusinessException.class,
                () -> harness.service.renew(lease.id(), renew("alice", "PT5M")));
        assertEquals(ErrorCode.LEASE_ALREADY_TERMINATED, exRenew.getCode(),
                "判定依据：已终止环境续租必须以可区分原因拒绝");
        log.info("已终止续租被拒绝 code={} message={}", exRenew.getCode(), exRenew.getMessage());

        BusinessException exTerminate = assertThrows(BusinessException.class,
                () -> harness.service.terminate(lease.id(), "alice"));
        assertEquals(ErrorCode.LEASE_ALREADY_TERMINATED, exTerminate.getCode());
        log.info("重复终止被拒绝 code={} message={}", exTerminate.getCode(), exTerminate.getMessage());
    }

    @Test
    @DisplayName("跨所有者操作被拒绝，原因与状态非法、配额不足相区分")
    void ownershipEnforced() {
        LeaseView lease = harness.service.apply(applyReq("req-owner", "alice", "payments", null));
        log.info("===== 输入：bob 对 alice 的租约 {} 执行终止 =====", lease.id());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> harness.service.terminate(lease.id(), "bob"));
        assertEquals(ErrorCode.NOT_OWNER, ex.getCode(),
                "判定依据：operator=bob 与 owner=alice 不符 -> NOT_OWNER");
        log.info("跨所有者终止被拒绝 code={} message={}", ex.getCode(), ex.getMessage());

        BusinessException exRenew = assertThrows(BusinessException.class,
                () -> harness.service.renew(lease.id(), renew("bob", null)));
        assertEquals(ErrorCode.NOT_OWNER, exRenew.getCode());
        assertEquals(LeaseStatus.READY, harness.service.get(lease.id()).status(),
                "判定依据：被拒绝的操作不得改变租约状态");
    }

    @Test
    @DisplayName("非法 ttl 输入被拒绝（默认语义之外的越界/非法格式），返回 VALIDATION_ERROR")
    void invalidTtlRejected() {
        BusinessException tooShort = assertThrows(BusinessException.class,
                () -> harness.service.apply(applyReq("req-ttl-1", "alice", "payments", "PT1S")));
        assertEquals(ErrorCode.VALIDATION_ERROR, tooShort.getCode());
        log.info("超短 TTL 被拒绝 code={} message={}", tooShort.getCode(), tooShort.getMessage());

        BusinessException badFormat = assertThrows(BusinessException.class,
                () -> harness.service.apply(applyReq("req-ttl-2", "alice", "payments", "10分钟")));
        assertEquals(ErrorCode.VALIDATION_ERROR, badFormat.getCode());
        log.info("非法 TTL 格式被拒绝 code={} message={}", badFormat.getCode(), badFormat.getMessage());

        BusinessException tooLong = assertThrows(BusinessException.class,
                () -> harness.service.apply(applyReq("req-ttl-3", "alice", "payments", "PT61M")));
        assertEquals(ErrorCode.VALIDATION_ERROR, tooLong.getCode());
        log.info("超长 TTL 被拒绝 code={} message={}", tooLong.getCode(), tooLong.getMessage());
    }

    @Test
    @DisplayName("不存在的租约返回 LEASE_NOT_FOUND，与业务冲突原因区分")
    void notFound() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> harness.service.terminate("nope", "alice"));
        assertEquals(ErrorCode.LEASE_NOT_FOUND, ex.getCode());
        log.info("不存在租约 code={} message={}", ex.getCode(), ex.getMessage());
    }
}
