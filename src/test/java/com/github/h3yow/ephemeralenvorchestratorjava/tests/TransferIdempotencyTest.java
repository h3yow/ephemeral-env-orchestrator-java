package com.github.h3yow.ephemeralenvorchestratorjava.tests;

import com.github.h3yow.ephemeralenvorchestratorjava.error.BusinessException;
import com.github.h3yow.ephemeralenvorchestratorjava.error.ErrorCode;
import com.github.h3yow.ephemeralenvorchestratorjava.tests.support.TestHarness;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.ApplyLeaseRequest;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.LeaseView;
import com.github.h3yow.ephemeralenvorchestratorjava.web.dto.TransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 转让重复提交：同键（含两端几乎同时提交）只真正生效一次、结论一致、目标配额不重复占用；
 * 同一 requestId 复用于另一条环境或另一个目标归属时，以 IDEMPOTENCY_KEY_CONFLICT
 * 明确拒绝，并能说明该键此前已用在哪。
 */
class TransferIdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(TransferIdempotencyTest.class);

    private TestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new TestHarness(5L, 100L);
    }

    private LeaseView apply(String requestId, String owner, String team) {
        ApplyLeaseRequest r = new ApplyLeaseRequest();
        r.setRequestId(requestId);
        r.setOwner(owner);
        r.setTeam(team);
        r.setTtl("PT10M");
        return harness.service.apply(r);
    }

    private TransferRequest transfer(String requestId, String operator, String owner, String team) {
        TransferRequest r = new TransferRequest();
        r.setRequestId(requestId);
        r.setOperator(operator);
        r.setTargetOwner(owner);
        r.setTargetTeam(team);
        return r;
    }

    @Test
    @DisplayName("串行重复提交同一次转让：只生效一次，重复请求拿到首次结果（归属仍是 bob），水位不叠加")
    void duplicateSerial() {
        LeaseView env = apply("apply-1", "alice", "teamA");
        LeaseView first = harness.service.transfer(env.id(), transfer("dup-s", "alice", "bob", "teamB"));
        LeaseView second = harness.service.transfer(env.id(), transfer("dup-s", "alice", "bob", "teamB"));

        assertEquals(first.id(), second.id());
        assertEquals("bob", second.owner());
        assertEquals(1L, harness.ledger.ownerView("bob").used());
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(1L, harness.ledger.teamView("teamB").used());
        assertEquals(0L, harness.ledger.teamView("teamA").used());
        log.info("串行重复转让复用唯一结果 owner={}", second.owner());
    }

    @Test
    @DisplayName("高并发同键转让：恰好生效一次，所有线程看到同一归属结论，目标只占一个配额")
    void duplicateConcurrent() throws Exception {
        LeaseView env = apply("apply-2", "alice", "teamA");
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<LeaseView> views = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();

        log.info("===== 输入：{} 线程同时用 requestId=dup-c 转让 leaseId={} alice->bob =====",
                threads, env.id());
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    views.add(harness.service.transfer(env.id(), transfer("dup-c", "alice", "bob", "teamB")));
                } catch (BusinessException ex) {
                    errors.add(ex.getCode().name());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        assertEquals(threads, views.size(), "判定依据：重复提交都应成功（幂等重放），而非报错");
        assertTrue(errors.isEmpty(), "判定依据：幂等命中不是错误：" + errors);
        Set<String> owners = new HashSet<>();
        views.forEach(v -> {
            owners.add(v.owner());
            assertEquals("teamB", v.team());
        });
        assertEquals(Set.of("bob"), owners, "判定依据：所有线程看到一致的最终归属");
        assertEquals(1L, harness.ledger.ownerView("bob").used(), "判定依据：目标 owner 只占一个配额");
        assertEquals(0L, harness.ledger.ownerView("alice").used());
        assertEquals(1L, harness.ledger.teamView("teamB").used());
        assertEquals(0L, harness.ledger.teamView("teamA").used());
        assertEquals(1, harness.provisioner.getSuccessCount(), "判定依据：转让不触发供给，次数恒为 1");
        log.info("并发 {} 次同键转让 -> 唯一生效，bob 水位={}", threads,
                harness.ledger.ownerView("bob").used());
    }

    @Test
    @DisplayName("同键转让另一条环境：IDEMPOTENCY_KEY_CONFLICT，消息说明键此前用在哪条环境，且第二条未被转让")
    void conflictDifferentLease() {
        LeaseView env1 = apply("apply-3a", "alice", "teamA");
        LeaseView env2 = apply("apply-3b", "alice", "teamA");
        harness.service.transfer(env1.id(), transfer("k1", "alice", "bob", "teamB"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> harness.service.transfer(env2.id(), transfer("k1", "alice", "bob", "teamB")));
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, ex.getCode());
        assertTrue(ex.getMessage().contains(env1.id()), "判定依据：冲突信息必须指出既往 leaseId");
        assertTrue(ex.getMessage().contains("bob"), "判定依据：冲突信息必须指出既往目标归属");
        log.info("同键转另一条环境 -> {}：{}", ex.getCode(), ex.getMessage());

        assertEquals("alice", harness.service.get(env2.id()).owner(), "判定依据：冲突请求不产生任何转让效果");
        assertEquals(1L, harness.ledger.ownerView("bob").used(), "判定依据：目标水位不重复增加");
    }

    @Test
    @DisplayName("同键转让同一环境但换目标归属：IDEMPOTENCY_KEY_CONFLICT，第一次结果不被覆盖")
    void conflictDifferentTarget() {
        LeaseView env = apply("apply-4", "alice", "teamA");
        harness.service.transfer(env.id(), transfer("k2", "alice", "bob", "teamB"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> harness.service.transfer(env.id(), transfer("k2", "bob", "carol", "teamC")));
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, ex.getCode());
        assertTrue(ex.getMessage().contains("teamB"));
        log.info("同键换目标 -> {}：{}", ex.getCode(), ex.getMessage());

        LeaseView unchanged = harness.service.get(env.id());
        assertEquals("bob", unchanged.owner());
        assertEquals("teamB", unchanged.team());
        assertEquals(0L, harness.ledger.ownerView("carol").used());
        assertEquals(0L, harness.ledger.teamView("teamC").used());
    }

    @Test
    @DisplayName("重复提交时目标字段留空（语义=保持当前归属）：仍按首次结果幂等命中，不报冲突")
    void duplicateWithBlankTargetsReplays() {
        LeaseView env = apply("apply-6", "alice", "teamA");
        harness.service.transfer(env.id(), transfer("k6", "alice", "bob", null));
        // 重复请求两个目标维度都留空：owner/team 都“保持当前”，即 bob/teamA，与首次登记一致
        LeaseView again = harness.service.transfer(env.id(), transfer("k6", "alice", null, " "));
        assertEquals("bob", again.owner());
        assertEquals("teamA", again.team());
        assertEquals(1L, harness.ledger.ownerView("bob").used());
        assertEquals(1L, harness.ledger.teamView("teamA").used());
        log.info("留空目标的重复提交幂等命中 owner={} team={}", again.owner(), again.team());
    }

    @Test
    @DisplayName("失败/被拒绝的转让不占用幂等键：同一 requestId 随后可被一次成功转让使用")
    void rejectedAttemptDoesNotConsumeKey() {
        LeaseView env = apply("apply-5", "alice", "teamA");
        // 非归属者发起，被 NOT_OWNER 拒绝
        assertThrows(BusinessException.class,
                () -> harness.service.transfer(env.id(), transfer("k3", "mallory", "bob", "teamB")));
        // 同键由真正的归属者发起，应当正常成功
        LeaseView moved = harness.service.transfer(env.id(), transfer("k3", "alice", "bob", "teamB"));
        assertEquals("bob", moved.owner());
        assertEquals(1L, harness.ledger.ownerView("bob").used());
        log.info("被拒请求不消费幂等键，同键后续成功转让生效");
    }
}
