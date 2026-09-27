# 临时环境租约编排服务（本地可运行）

用**本地内存状态**模拟临时环境的申请、供给、续租、**转让接手**与回收，重点保证在并发申请、重复提交、
租约过期、**转让与续租/终止/回收并发**等各种入口下，结论**确定、一致、可解释**。Spring Boot 3 / Java 21，无需外部依赖。

---

## 1. 快速运行

```bash
# 构建并运行全部测试（39 个用例，覆盖并发/幂等/竞态/配额边界/转让接手/配置）
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

# 续租 / 终止 / 失败重试 / 转让接手
curl -s -X POST localhost:8080/api/leases/{id}/renew     -H 'Content-Type: application/json' -d '{"operator":"alice","extension":"PT5M"}'
curl -s -X POST localhost:8080/api/leases/{id}/terminate -H 'Content-Type: application/json' -d '{"operator":"alice"}'
curl -s -X POST localhost:8080/api/leases/{id}/retry     -H 'Content-Type: application/json' -d '{"operator":"alice"}'

# 转让/接手（环境原地易主，不重建；transferRequestId 是转让幂等键）
curl -s -X POST localhost:8080/api/leases/{id}/transfer  -H 'Content-Type: application/json' \
  -d '{"transferRequestId":"tx-001","operator":"alice","targetOwner":"bob","targetTeam":"settlements"}'

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
- **转让/接手**：仅当前 owner 可把 `READY` 未到期环境原地转给新 owner/team，详见 [2.1](#21-租约转让--接手环境原地易主)。
- **非法流转被拒绝并给出可区分原因**（见错误码表），拒绝不会改变任何状态。

### 重复提交（幂等）

`requestId` 是调用方提供的幂等键，在全局临界区内以 `putIfAbsent` 原子登记。
同一 `requestId` 的重复提交（无论是串行还是高并发）都返回**同一个租约**，
只创建一次租约、只占用一次配额、只执行一次资源分配。

---

## 2.1 租约转让 / 接手（环境原地易主）

同事休假、项目交接、团队拆并时，环境**不用重建**：当前所有者可把一条已就绪的环境
**原地**转给新的所有者和/或团队。接手方拿到的是**同一个实例**（endpoint、资源槽、
到期时间都不变），只是配额归属从源头上挪到目标头上。

**什么状态能转、谁能发起**

- 仅 `READY` 且**尚未到期**的环境可以转让；`PENDING/PROVISIONING/FAILED` 一律以
  `TRANSFER_NOT_ALLOWED` 拒绝（状态原因与“已结束”区分开）；
- `TERMINATED` 以 `LEASE_ALREADY_TERMINATED` 拒绝，`EXPIRED` 以 `LEASE_ALREADY_EXPIRED`
  拒绝；若在判定瞬间恰好到期，则与续租路径一致地**原子回收**后再拒绝，已结束环境不可能转走；
- 发起人（`operator`）必须等于租约当前 owner，否则 `NOT_OWNER`。

**目标配额怎么判**

- 转让前在同一临界区内检查**目标归属**的 owner/team 两级配额，目标已满直接拒绝：
  原因分别为 `OWNER_QUOTA_EXCEEDED` / `TEAM_QUOTA_EXCEEDED`，与“状态不允许/非归属者/已结束”分得清；
- **只检查真正变化的维度**：同一团队内换⼈不检查团队水位，同⼀⼈换团队不检查个人水位；
  源归属自己腾出的槽位不重复计数。

**原子迁移与“源=目标”**

- “源释放、目标占用”与租约易主、幂等登记在**同一把全局锁临界区内一次性生效**：
  要么整体迁移，要么两边都不动，不会出现只扣一边或被扣两次的半更新；
- owner 相同则 owner 维度不动，team 相同则 team 维度不动；owner 与 team 都相同（自己转自己）
  时两边都不动，靠来回转让不可能凭空增加配额。

**重复提交与冲突**

- `transferRequestId` 是转让操作的幂等键（与申请阶段的 `requestId` 分开，两者也不得混用，
  混用以 `TRANSFER_REQUEST_CONFLICT` 拒绝）；
- 同一次转让被重复提交（含两端几乎同时点）只**真正生效一次**，目标配额只占一次，
  所有提交拿到一致结论。回放返回的是**转让生效那一刻的租约快照**，
  因此即使环境后来被新主人续租/终止，重复提交仍得到同一结论；
- 同一个 `transferRequestId` 被拿去转**另一条环境**，或转给**另一个目标归属**，
  不会被按第一次结果糊弄：以 `TRANSFER_REQUEST_CONFLICT` 明确拒绝，
  错误消息中说明该标识此前用在哪条环境、从谁转给谁、何时生效。

**转让成功之后**

- 新归属者可以正常**续租、终止**；原归属者再拿这条环境做任何操作都被 `NOT_OWNER` 挡住；
- 到期回收只认租约上的当前归属，释放的也是当前归属占用的配额。

**并发保证**

转让与续租、终止、到期回收并发时，所有“检查 + 状态/配额写入”仍由同一把全局锁串行裁决，
只有一个确定结论：已结束的环境不会被转走；转让没成功就绝不会吞掉目标配额；
一轮并发压完后，每个 owner、每个 team 的占用水位都与**实际仍存活（`quotaHeld`）的环境数**
逐条对得上。

---

## 3. 配额与归属语义

两级配额：**所有者（owner）**与**团队（team）**，任一到顶即拒绝。

| 拒绝原因 errorCode | 含义 | HTTP |
|---|---|---|
| `OWNER_QUOTA_EXCEEDED` | 所有者配额已满 | 409 |
| `TEAM_QUOTA_EXCEEDED` | 团队配额已满（跨 owner 累计） | 409 |
| `NOT_OWNER` | 操作者不是租约所有者（跨所有者操作） | 409 |
| `TRANSFER_NOT_ALLOWED` | 转让时环境当前状态不允许（非 READY 且未结束，如供给中/失败中） | 409 |
| `TRANSFER_REQUEST_CONFLICT` | 转让幂等键冲突：同一 `transferRequestId` 已用于另一条环境或另一个目标归属（消息中带此前用途） | 409 |
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
- requestId 申请幂等登记、transferRequestId 转让幂等登记、owner/team 配额检查与计数、
  租约状态翻转与归属迁移、时钟高水位更新都在锁内；
- 转让的“源释放 + 目标占用”是临界区内的一次账本迁移（`moveOwnership`），
  相同维度不计数；成功后留存生效时刻快照用于幂等回放；
- 供给资源槽（模拟资源池）的获取/回收在供给器内部原子完成，编排层保证成对释放。

---

## 8. 如何验证

```bash
mvn test
```

测试以题目关注的竞态与边界为主（均断言**确定结论 + 可区分原因**，并打印输入与判定依据）：

| 测试类 | 关注点 |
|---|---|
| `LifecycleStateTest` | 完整生命周期、非法流转原因区分、跨所有者拒绝、非法 TTL |
| `QuotaBoundaryTest` | owner/team 边界、超配额原因区分、终态释放后可再申请、失败占/释放配额口径 |
| `ConcurrentApplyTest` | 16 线程同一 requestId 只建一个租约/只分配一次；50 并发不同 requestId 精确不超卖 |
| `TransferTest` | 转让成功后归属与配额迁移、实例不变；目标 owner/team 配额满、非归属者、状态不允许、已终止/已回收等拒绝原因可区分；同键重复提交（含并发）只生效一次、改键改目标明确冲突；转让 vs 续租/终止/到期回收并发结论确定且水位与存活环境逐条对账 |
| `ExpiryRenewRaceTest` | 到期边界续租 vs 回收竞态（结论确定 EXPIRED）、边界前续租确定成功、时钟回拨拒绝且不提前回收 |
| `ProvisionFailureRetryTest` | 资源不足原因区分、失败记录保留、重试上限不静默成功、超限释放配额、**供给中并发终止无泄漏** |
| `ConfigurationValidationTest` / `InvalidConfigBootRejectTest` | 默认值稳定、非法/矛盾配置启动期拒绝 |
| `LeaseControllerIntegrationTest` | HTTP 状态码与稳定 errorCode、端到端幂等、转让成功/重复提交/冲突/原主人被挡 |

服务层在每次判定时记录结构化日志（输入 + 配额水位/到期时刻 + 判定依据），
`mvn test` 输出中可直接看到并发与竞态用例的裁决过程。
