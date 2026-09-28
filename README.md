# 临时环境租约编排服务（本地可运行）

用**本地内存状态**模拟临时环境的申请、供给、续租与回收，重点保证在并发申请、重复提交、
租约过期等各种入口下，结论**确定、一致、可解释**。Spring Boot 3 / Java 21，无需外部依赖。

---

## 1. 快速运行

```bash
# 构建并运行全部测试（61 个用例，覆盖并发/幂等/竞态/配额边界/转让/配置）
mvn test

# 启动服务（默认端口 8080）
mvn spring-boot:run
```

冒烟调用：

```bash
# 申请（requestId 为调用方幂等键）
curl -s -X POST localhost:8080/api/leases -H 'Content-Type: application/json' \
  -d '{"requestId":"req-001","owner":"alice","team":"payments","ttl":"PT10M"}'

# 查询 / 按 requestId 查询
curl -s localhost:8080/api/leases/{id}
curl -s "localhost:8080/api/leases?requestId=req-001"

# 续租 / 终止 / 失败重试
curl -s -X POST localhost:8080/api/leases/{id}/renew     -H 'Content-Type: application/json' -d '{"operator":"alice","extension":"PT5M"}'
curl -s -X POST localhost:8080/api/leases/{id}/terminate -H 'Content-Type: application/json' -d '{"operator":"alice"}'
curl -s -X POST localhost:8080/api/leases/{id}/retry     -H 'Content-Type: application/json' -d '{"operator":"alice"}'

# 转让 / 接手（requestId 是本次转让操作的幂等键；目标字段留空表示该维度保持不变）
curl -s -X POST localhost:8080/api/leases/{id}/transfer \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"tr-001","operator":"alice","targetOwner":"bob","targetTeam":"payments"}'
# 只换 owner（team 不动）
curl -s -X POST localhost:8080/api/leases/{id}/transfer \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"tr-002","operator":"alice","targetOwner":"bob"}'

# 配额
curl -s localhost:8080/api/leases/quotas/owners/alice
curl -s localhost:8080/api/leases/quotas/teams/payments
```

---

## 2. 生命周期规则

状态：`PENDING -> PROVISIONING -> READY -> TERMINATED`，另有失败与过期分支。

```
申请受理            供给成功                 主动终止
PENDING ───────▶ PROVISIONING ───────▶ READY ───────▶ TERMINATED
                    │  │  ▲               │
        供给失败     │  │  │ 重试(未达上限)  │ 到期(now >= expiresAt)
                    ▼  └──┘               ▼
                 FAILED ◀────────────── EXPIRED
                 （达到尝试上限即为终态）
```

- **申请**：校验输入与配额后，租约以 `PROVISIONING` 占用配额并同步执行一次模拟供给；
  成功即 `READY`，失败即 `FAILED`（保留失败记录）。
- **续租**：仅 `READY` 且未到期可续租；在**当前到期时间**基础上叠加延长时长。
- **终止**：`PENDING/PROVISIONING/READY/FAILED` 可由**所有者**主动终止；`TERMINATED/EXPIRED` 拒绝。
- **过期**：`READY` 在 `now >= expiresAt` 时被原子回收为 `EXPIRED`（定时扫描 + 查询惰性回收 + 续租/转让入口内联回收，各入口结论一致）。
- **转让**：仅当前 owner 可把 `READY` 未到期的环境原地转给新 owner/team，状态保持 `READY`、
  实例与到期时间不变，配额按维度原子搬运（详见 [2.5 节](#25-租约转让--接手环境原地易主)）。
- **非法流转被拒绝并给出可区分原因**（见错误码表），拒绝不会改变任何状态。

### 重复提交（幂等）

`requestId` 是调用方提供的幂等键，在全局临界区内以 `putIfAbsent` 原子登记。
同一 `requestId` 的重复提交（无论是串行还是高并发）都返回**同一个租约**，
只创建一次租约、只占用一次配额、只执行一次资源分配。

---

## 2.5 租约转让 / 接手（环境原地易主）

同事休假、项目交接、团队拆并时，环境无需重建，只需把归属从一个人（或团队）挪到另一个人
（或团队）头上：**不重新供给、不换实例，`endpoint`/资源槽/到期时间全部不变**。

```
POST /api/leases/{id}/transfer
{"requestId":"tr-001","operator":"alice","targetOwner":"bob","targetTeam":"payments"}
```

- `operator`：发起人，**必须是租约当前 owner**，否则 `NOT_OWNER`（易主后原 owner 再操作也被挡）。
- `targetOwner` / `targetTeam`：目标归属；**留空表示该维度保持不变**（可只换人、只换团队）。
  两个维度都与现状相同属于空转让，以 `VALIDATION_ERROR` 拒绝。

**什么状态能转**

| 条件 | 结论 | errorCode |
|---|---|---|
| `READY` 且 `now < expiresAt` | 受理转让 | 成功 |
| 已终止 | 拒绝 | `LEASE_ALREADY_TERMINATED` |
| 已到期（含恰好在到期点提交） | 原子回收并拒绝，不会把已结束环境转走 | `LEASE_ALREADY_EXPIRED` |
| `PENDING/PROVISIONING/FAILED` 等非就绪状态 | 拒绝 | `LEASE_NOT_TRANSFERABLE` |
| 发起人不是当前 owner | 拒绝 | `NOT_OWNER` |
| 环境不存在 | 拒绝 | `LEASE_NOT_FOUND` |

**目标配额怎么判**

- 转让前先在同一临界区内检查**目标 owner、目标 team** 两级配额（owner 优先），
  任一到顶即整单拒绝：`OWNER_QUOTA_EXCEEDED` / `TEAM_QUOTA_EXCEEDED`，两边水位都不动。
- 某一维度源与目标相同（例如只换 owner 不换 team）时，**该维度跳过**：不先减后加、不重复计数。
- 转让生效 = 源释放 + 目标占用 + 归属改写 + 幂等登记，在同一把全局锁内一次完成，
  因此要么整体生效、要么两边不动，不存在“只扣一边/被扣两次”的半更新状态；
  来回转让也不会凭空把配额变多。
- 转让成功后，**新 owner 可正常续租/终止**；原 owner 对该环境的一切操作均以 `NOT_OWNER` 拒绝。

**重复提交与冲突**

- 转让有自己独立的幂等键空间（`requestId` 与申请互不干扰）。同一 `requestId` 重复提交
  （含两端几乎同时提交）只真正转让一次，所有提交拿到同一结论，目标配额只占一次；
  重复请求中留空的目标维度按“保持当前归属”归一化后比对。
- 同一 `requestId` 被拿去转**另一条环境**，或转给**另一个目标归属**，服务端不会悄悄回放
  第一次的结果，而是以 `IDEMPOTENCY_KEY_CONFLICT` 明确拒绝，错误信息会说明该键此前已用于
  哪条 leaseId、转给了谁。
- **被拒绝的转让不消费幂等键**：同一个键随后仍可用于一次成功的转让。

**并发下保证什么**

转让与续租、终止、到期回收都走同一把全局锁串行裁决，任何时刻**只有一个确定结论**，
不依赖线程调度顺序、可稳定复现：

- **结论唯一**：同一条环境上转让与终止几乎同时发起时，谁的临界区先提交谁生效，
  但无论谁先谁后，全局只会出现一种终局：
  - 终止先落定 → 环境 `TERMINATED`，之后**全部**转让以 `LEASE_ALREADY_TERMINATED` 失败；
  - 转让先落定 → 环境原地易主且仍 `READY`（同一环境只会易主一次），
    原归属者此后的转让/续租/终止全部被 `NOT_OWNER` 挡下，只有新归属者能继续操作。
- **已结束环境不可转让**：`TERMINATED`/`EXPIRED` 的环境绝不可能再被转走；
  恰好在到期点提交的转让会与续租/回收入口一样触发**原子回收**，
  随后以 `LEASE_ALREADY_EXPIRED` 失败，`READY->EXPIRED` 全局至多翻转一次。
- **配额守恒（不被吞、不半更新）**：转让未成功时，源 owner/team 不释放、
  目标 owner/team 不占用，两级水位在任何交错顺序下都纹丝不动；
  转让成功则归属与两级配额在同一临界区一起迁移，成功数恒为 1，
  且是**环境原地易主**（`id`/`endpoint`/资源槽/到期时间都不变，不重新供给）。
- 到期边界上转让与回收并发：租约被原子回收，所有转让以 `LEASE_ALREADY_EXPIRED` 失败。
- 一轮并发压测结束后，每个 owner、每个 team 的账本占用水位都与其**名下仍存活
  （`quotaHeld`）的环境数严格相等**，资源槽数量同样对得上（见 `TransferConcurrencyTest`）。

> 测试侧为了不把正确性寄托在调度运气上，竞态用例采用两层覆盖：
> ① 同一起跑线的**真实并发**并连续跑多轮，断言两种胜出顺序下各自必须成立的完整结论
> （而非放松成“怎么跑都过”的弱断言）；② **分阶段确定性用例**用栅栏把
> “谁的临界区先提交”钉死，使“终止先 / 转让先 / 先转让后到期”等每一种交错都能稳定复现。

---

## 3. 配额与归属语义

两级配额：**所有者（owner）**与**团队（team）**，任一到顶即拒绝。

| 拒绝原因 errorCode | 含义 | HTTP |
|---|---|---|
| `OWNER_QUOTA_EXCEEDED` | 所有者配额已满 | 409 |
| `TEAM_QUOTA_EXCEEDED` | 团队配额已满（跨 owner 累计） | 409 |
| `NOT_OWNER` | 操作者不是租约所有者（跨所有者操作；转让后原 owner 再操作也是此码） | 409 |
| `LEASE_NOT_TRANSFERABLE` | 环境当前状态不允许转让（仅 READY 未到期可转） | 409 |
| `IDEMPOTENCY_KEY_CONFLICT` | 转让 requestId 已被另一次转让使用（换了环境或换了目标归属） | 409 |
| `RESOURCE_INSUFFICIENT` | 配额通过但底层资源池容量不足（供给失败） | 体现在失败记录 `RESOURCE_POOL_EXHAUSTED` |
| `INVALID_STATE_TRANSITION` / `LEASE_NOT_READY` / `LEASE_ALREADY_TERMINATED` / `LEASE_ALREADY_EXPIRED` / `RETRY_LIMIT_EXCEEDED` | 各类非法状态流转，彼此可区分 | 409 |
| `CLOCK_ROLLBACK_DETECTED` | 检测到系统时间回拨 | 409 |
| `VALIDATION_ERROR` | 入参/TTL 非法 | 400 |
| `LEASE_NOT_FOUND` | 租约不存在 | 404 |

**配额占用口径**（关键设计，避免“失败长期占配额”与“失败即无限冲击资源池”两个极端）：

- 申请**受理即计数**：`PENDING/PROVISIONING/READY` 以及**仍可重试的 `FAILED`** 都占用配额；
- 进入终态即释放：`TERMINATED`、`EXPIRED`、**达到尝试上限的 `FAILED`**；
- 配额的“检查 + 计数”与租约状态变更在**同一把全局锁**内完成，故并发下绝不超卖；
- 释放通过租约上的幂等标志保证**至多一次**，终止/过期/失败超限三条路径都不会重复释放。

---

## 4. 续租、过期与时钟安全

- **到期边界判定与状态翻转不可分割**：在同一临界区内完成“是否到期 → 回收/延长”。
  因此回收与续租并发时只有一方胜出：
  - `now < expiresAt`：续租成功，到期时间顺延；
  - `now >= expiresAt`：续租被拒绝（`LEASE_ALREADY_EXPIRED`），租约被原子回收并释放配额与资源，
    **绝不会出现已结束环境仍被续租**。
- **续租叠加规则**：新到期时间 = 原 `expiresAt + extension`（而非 `now + extension`），
  语义稳定且可预测。
- **时钟回拨保护**：所有时间敏感写操作（申请/续租/回收扫描）经统一取时入口，维护单调高水位。
  一旦发现 `now` 早于已观测时间，立即以 `CLOCK_ROLLBACK_DETECTED` 拒绝该操作：
  - 回拨时间下的回收扫描**跳过本轮**，不会提前回收有效租约；
  - 回拨时间下的续租被拒绝，不会延长已结束/有效租约的真实到期边界；
  - 时钟恢复并追过高水位后，服务自动恢复正常。
- **供给期间被终止**：供给动作在锁外执行，完成后重新入锁裁决；若期间租约已终止，
  终态优先，已分配到的资源槽被立即回收，杜绝“供给晚到导致资源泄漏 / READY 覆盖 TERMINATED”。

---

## 5. 供给失败的重试

- 每次供给尝试（含首次）都会写入**不可变失败记录**：尝试序号、原因码、信息、时间，
  成功后历史记录仍保留，可审计可解释。
- 原因码区分资源池耗尽（`RESOURCE_POOL_EXHAUSTED`）与其它瞬时/异常失败。
- 最多尝试 `orchestrator.max-provision-attempts` 次（含首次）：
  - 未达上限：`FAILED` 可由所有者调用 `retry` 再次供给，期间继续占用配额；
  - 达到上限：`FAILED` 成为**终态**，再重试以 `RETRY_LIMIT_EXCEEDED` 明确拒绝，
    **绝不静默视为成功**，同时立即释放配额（失败不长期占用）。
- 供给器抛出的异常会被捕获并转成失败记录，不会让请求线程带着泄漏状态崩溃。

---

## 6. 配置语义

前缀 `orchestrator.properties`，缺失即采用确定默认值；**非法/矛盾配置启动期直接失败**，
而不是静默忽略。

| 配置项 | 默认值 | 约束 |
|---|---|---|
| `default-ttl` | `PT10M` | 正时长，且须落在 `[min-ttl, max-ttl]` |
| `renewal-extension` | `PT5M` | 正时长 |
| `min-ttl` / `max-ttl` | `PT1M` / `PT1H` | 正时长，min ≤ max |
| `owner-quota` | `5` | `-1` 不限或正整数（0 非法） |
| `team-quota` | `20` | `-1` 不限或正整数（0 非法） |
| `max-provision-attempts` | `3` | ≥ 1（`@Min` 注解约束） |
| `resource-pool-capacity` | `10` | ≥ 0 |
| `reaper-interval` | `PT30S` | 正时长 |

校验由两层组成：JSR-303 注解（绑定阶段）+ `OrchestratorProperties.validate()`（一致性）。
`InvalidConfigBootRejectTest` 验证非法配置确实阻止应用启动。

---

## 7. 并发模型（实现说明）

- 单把公平 `ReentrantLock` 串行化所有“检查 + 状态/配额写入”临界区，结论天然一致；
- 供给等外部动作在锁外执行，完成后再入锁裁决，避免慢供给阻塞全局；
- requestId 幂等登记（申请与转让各一套键空间）、owner/team 配额检查与计数、
  转让的“源释放 + 目标占用 + 归属改写”原子搬运、租约状态翻转、时钟高水位更新都在锁内；
- 供给资源槽（模拟资源池）的获取/回收在供给器内部原子完成，编排层保证成对释放。

---

## 8. 如何验证

```bash
# 全量用例（61 个，覆盖申请/续租/终止/到期回收/转让/配额/幂等/竞态/配置）
mvn test

# 只跑转让并发用例
mvn test -Dtest=TransferConcurrencyTest
```

### 本地反复验证并发用例（确认“跑多少遍都稳定”）

并发结论由全局锁保证，单次全绿可能只是没覆盖到某种调度顺序。建议多跑几遍：

```bash
# 连续 10 次执行转让并发类，任意一次出现 Failures 非 0 即不稳定
for i in $(seq 1 10); do
  mvn test -Dtest=TransferConcurrencyTest 2>&1 | grep 'Tests run:'
done
```

`TransferConcurrencyTest` 内部同时用两种手段保证“既能稳定复现问题、也能稳定通过”：

- **真实并发 + 多轮**：`transferVsTerminateRace` 连续 24 轮全新环境上 12 转让 vs 12 终止
  同一起跑，并断言两种胜出顺序都确实出现过；任一分支的结论写得不对，对应分支每轮都会红，
  不会再出现“跑几遍红几次”。
- **确定性交错**：`terminateCommitsFirst_thenTransferRejected`、
  `transferCommitsFirst_thenOnlyNewOwnerCanTerminate`、`interleavedTerminateCommitsBeforeWaitingTransfer`、
  `transferFirst_thenReapReleasesNewOwnerQuota` 用分阶段/栅栏把提交顺序钉死，
  分别稳定复现“终止先”“转让先”“转让在门口等待时终止先落定”“先转让后到期回收”。
- **失败转让守恒**：`failedTransferConservesQuotaUnderConcurrency`、
  `failedTransferVsTerminateRace` 验证目标配额满时，并发/与终止交错下源不释放、目标不占用、
  被拒不消费幂等键（腾槽后同键可成功）。

测试以题目关注的竞态与边界为主（均断言**确定结论 + 可区分原因**，并打印输入与判定依据）：

| 测试类 | 关注点 |
|---|---|
| `LifecycleStateTest` | 完整生命周期、非法流转原因区分、跨所有者拒绝、非法 TTL |
| `QuotaBoundaryTest` | owner/team 边界、超配额原因区分、终态释放后可再申请、失败占/释放配额口径 |
| `ConcurrentApplyTest` | 16 线程同一 requestId 只建一个租约/只分配一次；50 并发不同 requestId 精确不超卖 |
| `ExpiryRenewRaceTest` | 到期边界续租 vs 回收竞态（结论确定 EXPIRED）、边界前续租确定成功、时钟回拨拒绝且不提前回收 |
| `ProvisionFailureRetryTest` | 资源不足原因区分、失败记录保留、重试上限不静默成功、超限释放配额、**供给中并发终止无泄漏** |
| `TransferSuccessTest` | 转让后归属/配额迁移、环境原地不变（不重新供给/不换实例）、新 owner 可续租终止且原 owner 被挡、相同维度不重复计数、往返守恒、水位对账 |
| `TransferRejectTest` | 非归属者/目标 owner 或 team 配额满/非 READY/已终止/已到期/不存在/空转让等拒绝原因彼此可区分，且拒绝后两边水位不动 |
| `TransferIdempotencyTest` | 同键串行与 24 线程并发只生效一次、目标配额不重复占用；同键换环境/换目标 `IDEMPOTENCY_KEY_CONFLICT` 且信息可追溯；被拒不消费键 |
| `TransferConcurrencyTest` | 转让 vs 终止/续租/到期回收的**多轮真实竞态 + 确定性交错**：唯一结论、原地易主与两级配额迁移、已结束不可转让、失败转让配额守恒；600 轮混合并发后逐 owner、逐 team 水位与存活环境数严格对账 |
| `ConfigurationValidationTest` / `InvalidConfigBootRejectTest` | 默认值稳定、非法/矛盾配置启动期拒绝 |
| `LeaseControllerIntegrationTest` | HTTP 状态码与稳定 errorCode、端到端幂等 |

服务层在每次判定时记录结构化日志（输入 + 配额水位/到期时刻 + 判定依据），
`mvn test` 输出中可直接看到并发与竞态用例的裁决过程。
