# 临时环境租约编排服务（Ephemeral Environment Lease Orchestrator）

一个可在**本地直接运行**的临时环境申请/供给/续租/回收编排服务。使用**内存状态 + 本地资源池**模拟真实环境供给，
重点保证：**并发申请不超卖、重复提交不重复分配、租约到期回收与续租并发时结论确定且可解释**。

基于 Spring Boot 3.4 / Java 21，无外部依赖（无需数据库）。

---

## 1. 快速开始

```bash
# 构建与测试
mvn test

# 本地运行（默认端口 8080）
mvn spring-boot:run

# 用短 TTL 体验到期自动回收（租约 3 秒、扫描 1 秒、所有者配额 2）
ORCH_LEASE_TTL_SECONDS=3 ORCH_SWEEP_FIXED_DELAY_MS=1000 ORCH_OWNER_QUOTA=2 \
  mvn spring-boot:run
```

---

## 2. HTTP API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/leases` | 申请环境（`requestId` 幂等），同步完成供给 |
| POST | `/api/leases/{envId}/renew?operator=` | 续租 |
| POST | `/api/leases/{envId}/terminate?operator=` | 主动终止（终态重复终止幂等） |
| POST | `/api/leases/{envId}/retry?operator=` | 供给失败后重试 |
| GET | `/api/leases/{envId}` | 查询单个环境（读取时惰性判期） |
| GET | `/api/leases?owner=&team=` | 列表（可按所有者/团队过滤） |
| POST | `/api/leases/sweep` | 手动触发到期回收扫描，返回 `{"reclaimed":n}` |
| GET | `/api/leases/quotas/owners/{owner}` | 所有者配额 `used/limit` |
| GET | `/api/leases/quotas/teams/{team}` | 团队配额 `used/limit` |

申请请求体：

```json
{ "requestId": "req-001", "owner": "alice", "team": "team-a" }
```

- `requestId`：**幂等键**。重复提交相同 `requestId` 返回同一环境且 `duplicate=true`，绝不重复分配；
  相同 `requestId` 但 `owner/team` 不同 → `409 IDEMPOTENCY_CONFLICT`。
- `team` 缺省（空/null）时归属于与 `owner` 同名的团队。

返回的 `LeaseView` 含：`status / createdAt / readyAt / expiresAt / renewedCount / resourceId / failures[] / terminalMessage`。

### 拒绝原因 → HTTP 状态码（确定、可区分）

| reason | HTTP | 含义 |
| --- | --- | --- |
| `INVALID_REQUEST` | 400 | 入参为空等 |
| `NOT_OWNER` | 403 | 跨所有者操作 |
| `ENV_NOT_FOUND` / `IDEMPOTENCY_KEY_NOT_FOUND` | 404 | 环境 / 幂等键不存在 |
| `ILLEGAL_STATE_TRANSITION` | 409 | 当前状态不允许该操作 |
| `LEASE_EXPIRED` | 409 | 续租时发现租约已到期（并已完成回收） |
| `RETRY_LIMIT_EXHAUSTED` | 409 | 达到供给重试上限，禁止再重试 |
| `IDEMPOTENCY_CONFLICT` | 409 | 幂等键与请求体归属冲突 |
| `OWNER_QUOTA_EXCEEDED` / `TEAM_QUOTA_EXCEEDED` | 429 | 所有者 / 团队配额超限 |
| `INSUFFICIENT_RESOURCES` | 503 | 资源不足（防御保留） |
| `PROVISION_FAILED` | 502 | 供给器失败（防御保留） |

> 资源池容量不足在本地实现中表现为一次**可解释、可重试的供给失败**
> （`failures[].reasonCode = RESOURCE_SHORTAGE`，环境进入 `FAILED` 且不占用配额），
> 从而与“配额超限”（请求直接被 429 拒绝）明确区分。

---

## 3. 生命周期规则

```
                 +---------- 供给成功 ----------+
                 v                              |
 PENDING --> PROVISIONING --> READY --续租--> READY
                 |                |
              供给失败          到期回收
                 v                v
              FAILED -------> EXPIRED / TERMINATED（终态）
```

- **申请**：受理后同步供给。成功 → `READY`（分配 `resourceId`、置 `expiresAt`、占用配额）；
  失败 → `FAILED`（追加失败记录、立即释放资源与配额，失败不长期占用配额）。
- **续租**：仅 `READY` 可续租；每次把到期时间延长为 `now + extend-seconds`，`renewedCount+1`。
  续租时若已到期：**先执行回收**，再以 `LEASE_EXPIRED` 拒绝，绝不会“已结束仍被续租”。
- **终止**：`READY / PENDING / PROVISIONING / 未耗尽的 FAILED` 可终止 → `TERMINATED` 并释放资源；
  对已 `TERMINATED` 环境重复终止为幂等无操作；对 `EXPIRED` 或重试耗尽的 `FAILED` 终止 → `ILLEGAL_STATE_TRANSITION`。
- **重试**：仅 `FAILED` 且尝试次数 `< max-attempts` 可重试；重新检查并占用配额后再供给。
  达到上限 → 失败终态，返回 `RETRY_LIMIT_EXHAUSTED`，**绝不静默视为成功**。
- **失败记录**：每次失败的 `attempt / occurredAt / reasonCode / detail` 都持久保留在 `failures[]`，
  即使后续重试成功也不丢失，保证失败可解释。
- **回收**：后台定时扫描 + 读取惰性判期两条路径，到期的 `READY` 环境进入 `EXPIRED` 并释放资源/配额。

非法状态流转一律以 `ILLEGAL_STATE_TRANSITION` 拒绝，并与归属不符、配额超限、资源不足等原因区分。

---

## 4. 配额与归属语义

- 两级配额：**所有者配额** `owner-quota` 与**团队配额** `team-quota`，两者在分配前**同时**检查；
  超团队配额与超所有者配额分别返回 `TEAM_QUOTA_EXCEEDED` / `OWNER_QUOTA_EXCEEDED`。
- **占用计数**只统计尚未释放的环境（`PENDING/PROVISIONING/READY`）。
  供给失败立即归还资源，`FAILED` 不占用配额；终止/回收后配额立即可用。
- 申请在“检查配额 → 建租约 → 供给”**同一把锁内**完成，并发申请下成功数精确等于配额，**不超卖**。
- **归属校验**：续租/终止/重试的 `operator` 必须等于环境 `owner`，否则 `403 NOT_OWNER`，
  团队成员关系不代表可跨所有者操作。

---

## 5. 过期与时钟安全

- 过期判定：`now >= expiresAt` 即到期。时间来自 `SafeClock`：它记录“已观测到的最大时间”，
  当系统时钟**回拨**（NTP 校时/人为调整）时沿用上一次观测值，**时间只前进不倒退**。
  因此回拨不会让有效租约被提前回收，也不会改变已计算好的 `expiresAt`。
- 环境一旦进入 `EXPIRED/TERMINATED` 终态由状态机本身锁定，时钟如何变化都不会“复活”或延长已结束租约。
- **续租/回收竞态**：所有写操作与扫描都竞争编排器的同一把实例锁，串行化后只有两种确定结论：
  续租成功（仍 `READY`，持资源、占配额）或已回收（`EXPIRED`，资源与配额各只释放一次）。
  资源释放另有幂等守卫，重复回收同一资源不会二次递减计数。

---

## 6. 配置项（默认语义与非法配置）

配置缺失时使用**确定且稳定的默认值**；任何非法值（≤0）在启动 `@PostConstruct` 校验中**直接令应用启动失败**，
绝不静默忽略；类型完全不匹配则在 Spring 绑定阶段失败。

| 配置键 | 环境变量 | 默认 | 约束 |
| --- | --- | --- | --- |
| `orchestrator.defaults.owner-quota` | `ORCH_OWNER_QUOTA` | 3 | 正整数 |
| `orchestrator.defaults.team-quota` | `ORCH_TEAM_QUOTA` | 8 | 正整数 |
| `orchestrator.lease.ttl-seconds` | `ORCH_LEASE_TTL_SECONDS` | 300 | 正整数 |
| `orchestrator.lease.extend-seconds` | `ORCH_LEASE_EXTEND_SECONDS` | 300 | 正整数 |
| `orchestrator.provision.max-attempts` | `ORCH_PROVISION_MAX_ATTEMPTS` | 3 | 正整数 |
| `orchestrator.resources.pool-size` | `ORCH_RESOURCE_POOL_SIZE` | 20 | 正整数 |
| `orchestrator.sweep.fixed-delay-ms` | `ORCH_SWEEP_FIXED_DELAY_MS` | 60000 | 正整数 |

---

## 7. 如何验证

```bash
mvn test
```

测试以需求强调的竞态/边界为主，共 25 个用例，且**每个用例的日志都打印“输入 + 判定依据”**：

- `SafeClockTest`：系统时间回拨时时钟不倒退，有效租约不被提前回收。
- `LifecycleTest`：申请→就绪→终止完整链路；终态后续租被拒；跨所有者 `NOT_OWNER` 与状态非法区分。
- `IdempotencyTest`：同一 `requestId` 多次提交只分配一次；同键不同归属 `IDEMPOTENCY_CONFLICT`。
- `QuotaTest`：所有者/团队配额边界精确拒绝、原因互相区分；资源不足表现为可重试失败且释放占位；释放后重试成功。
- `LeaseExpiryTest`：扫描回收释放配额；到期续租返回 `LEASE_EXPIRED`；回拨不提前回收；终态不可续租。
- `ProvisionFailureTest`：失败记录累积保留、失败不占配额、第三次重试成功、达到上限 `RETRY_LIMIT_EXHAUSTED`。
- `ConcurrencyTest`（重点）：
  - 50 线程齐发、配额=5 → 恰好 5 个成功，其余全部稳定归因为 `OWNER_QUOTA_EXCEEDED`，资源占用=5；
  - 32 线程同一 `requestId` 齐发 → 唯一环境、唯一资源、仅 1 次配额占用；
  - 到期瞬间续租与扫描并发 → 结果只可能是续租成功或已回收，资源/配额不出现半更新。
- `OrchestratorPropertiesTest`：空配置默认值稳定；0/负数与非数字配置被明确拒绝。
- `LeaseControllerIntegrationTest`：各业务原因在 HTTP 入口上映射到确定状态码与 `reason`。

手动验证（短 TTL）：

```bash
ORCH_LEASE_TTL_SECONDS=3 ORCH_SWEEP_FIXED_DELAY_MS=1000 mvn spring-boot:run
curl -s -X POST localhost:8080/api/leases -H 'Content-Type: application/json' \
  -d '{"requestId":"demo-1","owner":"alice","team":"team-a"}'
# 约 3 秒后 GET /api/leases/{envId} 可见 status=EXPIRED 与 terminalMessage
```

---

## 8. 代码结构

```
domain/        状态机、拒绝原因、租约快照、失败记录、业务异常
clock/         SafeClock：单调安全（防系统时间回拨）
provision/     ResourceProvisioner / LocalResourceProvisioner：固定容量资源池 + 失败注入
config/        OrchestratorProperties（默认值 + 启动校验）、基础设施 Bean
service/       EnvironmentOrchestrator（核心：锁内完成校验-判定-变更）、到期扫描调度器
web/           LeaseController、ApiExceptionHandler（reason → HTTP 状态码）
```
