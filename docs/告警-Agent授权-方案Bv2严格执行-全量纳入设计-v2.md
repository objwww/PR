# 告警 Agent 授权体系：方案 B v2 安全不变量基线·Mutation Safety Plane 设计 v2（两轮评审吸收定稿）

日期：2026-09-15（第二轮评审吸收定稿）
状态：AM8 正式技术设计基线候选——两轮真实代码链路评审意见已全部吸收
准绳：**严格满足方案 B v2 的安全不变量（capability/invariant baseline）**，不要求实现形式逐字一致；现有比 B v2 更强的机制（DeterministicSupervisor 等）不为了矩阵变绿而改弱
验收清单：《方案Bv2严格对照-符合性矩阵-v1》按本文件 §0.1 判定规则重读

---

## 修订记录

### 第一轮（14 条）：顺序反转与架构边界

| # | 评审修正 | 落点 |
|---|---|---|
| 1 | 解封 R2/R3 不是第一步；先搭安全链 shadow/validate-only 验证 | §0.3、§5 |
| 2 | "L6 整层 ❌"分解为子域（Capability ✅ / Scope ❌ / Approval ❌） | §0.2 |
| 3 | correctness kernel 清单不重写，只作基础设施层 | §0.2 |
| 4 | Worker 提出意图、Action Runner 执行；LeaseFence 保护不了已发出的外部副作用 | §0.4、§2.0 |
| 5 | ActionDigest 升级为授权身份锚 | §2.3 |
| 6 | Scope 不信任告警标签，Resource Resolver 产出 canonical identity | §2.4 |
| 7 | Resource Coordinator 粒度 = Operation × resource | §2.11 |
| 8 | UNKNOWN 升级为 reconcile 入口 | §2.12 |
| 9 | 三租约不可混；generation 与 epoch 双层 ABA 保留 | §2.11 |
| 10 | Progress 分 activity / meaningful progress | §3.2 |
| 11 | Supervisor 保留 authority | §3.1 |
| 12 | HMAC 并行、Redis 观察项 | §4 |
| 13 | Eval 先行，回归不变量进 CI | §5、§6 |
| 14 | 演进路线 Phase A–E | §5 |

### 第二轮（17 条，本版）：语义冲突修正与准绳收敛

| # | 优先级 | 评审修正 | 落点 |
|---|---|---|---|
| R1 | P0 | **审批不得绑定瞬时 lease_epoch**（durable suspension 恢复后 epoch 必变 → 永远消费失败）；Approval 验 Action，LeaseFence 验 Executor | §2.6 |
| R2 | P0 | **Transactional Operation Outbox**：consume→crash 窗口（审批已废、动作未执行）由同事务 outbox 关闭 | §2.10 |
| R3 | P0 | **Resource Lock 释放受 Operation 状态约束**：UNKNOWN/RECONCILING 期间 BUSY 不释放，防 external side-effect zombie；锁内 epoch 改名 resource_epoch | §2.11 |
| R4 | P0 | **HMAC 永远验证**，Redis 只影响 nonce 防重放；故障期降级为本地 nonce cache + 降级审计，绝不退回 bearer；签名覆盖 method/path/source_id/key_id | §4 L0 |
| R5 | P1 | Progress 事件→activity/progress 映射表补全（tool heartbeat、重复 query_logs 的归类） | §3.2 |
| R6 | P1 | Scope 信任根：HMAC 只证明"该 source 发过该数据"，不证明 env/resource 标签是授权信息；支持 **authorized_scope_expansion**（根因在初始资源集之外时，扩张须重新 Policy/Approval） | §2.4 |
| R7 | P1 | approvals JSONB 数组 → 独立 **approval_decisions 表**（UNIQUE 约束防丢更新）；双人确认 = two distinct principals（一人兼两角色不算） | §2.7 |
| R8 | P1 | **session 与 single-use 矛盾** → Request / Grant / Consumption 三层拆开：grant 可复用、每次副作用消费一个 single-use OperationAuthorization | §2.8 |
| R9 | P1 | 哈希链说法收敛为"**tamper-evident under the assumed DB write boundary**"；增强 = Merkle checkpoint 签名写外部 WORM；Runner 验证 checkpoint→segment，不从 genesis 全量验 | §2.14 |
| R10 | P1 | Replay A 先定 source of truth：走**路线 B（事务状态 + 审计事件）**，业务表为事实源；Replay A = 事件重建等价 workflow view 与业务表 snapshot 比对 | §3.4 |
| R11 | P1 | **P0"不丢"与 429 冲突**：两层拆开——Edge Abuse Protection（429 合法）与 Work Admission（durable 后 P0 只能 DEFERRED 不能 reject） | §4 L1 |
| R12 | P1 | INVESTIGATING 禁止新 run 有误：唯一硬约束是"**一次一个 active run**"（uq 部分唯一索引），run 失败后重新生成必须允许 | §4 L2 |
| R13 | P1 | Zombie reaper 的 cancel/reschedule 竞态：**Cancel requested ≠ cancelled**——CANCEL_REQUESTED→ABORTING→TERMINATED 才能重排；有 DISPATCHED/UNKNOWN operation 先 reconcile | §3.3 |
| R14 | P1 | **删除"模型自由分派必须建设"硬要求**：deterministic orchestration 可为永久终态；矩阵允许"capability exists + evaluated + policy 裁定更保守"判 ✅ | §0.1、§3.1 |
| R15 | P1 | Deadline 拆双时钟：incident_wall_deadline 永不冻结（SLO 诚实）/ system_active_budget 审批期冻结；三指标 wall_clock_mttm / system_active_time / human_wait_time | §2.9 |
| R16 | P1 | 调度 Fairness：effective_priority = base + aging；抢占三前置（checkpoint 已提交 ∧ 无在途 mutation ∧ 不在资源锁临界区） | §4 L3 |
| R17 | P1 | Eval 不等到最后：**E0 Eval Harness Skeleton 最先落**（baseline/invariant framework/fault fixture/candidate-vs-baseline），每个 Phase 携带自己的 eval | §5 |

---

## 0. 总裁定

### 0.1 准绳：严格满足 B v2 的安全不变量，不是"58 行全绿"

- B v2 是 **capability / invariant baseline**，不是逐字实现的规格书；
- 矩阵判定规则修订：某条判 ✅ 当且仅当——**capability exists + 已被 eval 覆盖 + policy 裁定采用同等或更强的机制**。允许"机制不同、风险面相同且已实证"的 ✅（如 DeterministicSupervisor 对 LLM 自由分派）；
- 禁止为对齐参考架构而扩大攻击面或改弱已有更强机制；
- 现有 correctness kernel 保留清单（基础设施层，不重写）：

```text
uq_rca_run_active_incident    generation fence        lease_epoch
LeaseFence                    revision/CAS            RunBudgetGate
ToolPolicy 双闸               RcaActionGuard          canonical ActionDigest
UNKNOWN invocation            DeterministicSupervisor DoomLoopGuard
```

### 0.2 L6 子域口径

| Safety 子域 | 现状 |
|---|---|
| Capability Enforcement（ToolPolicy 双闸/ToolRisk 本地可信/schema/RcaActionGuard） | ✅ 强 |
| Execution Fencing（generation+epoch+LeaseFence+CAS） | ✅ 强 |
| Scope / Policy Authorization | ❌（§2.4 建）|
| Human Approval | ❌（§2.6~2.10 建）|
| Action Runner Sandbox | ⚠️ |
| Mutation Reconciliation | ⚠️（只读域 UNKNOWN 已有，mutation 域随 Phase B/C 建）|

### 0.3 顺序总裁定：解封 R2/R3 不是第一步

"高危副作用物理上不存在"是资产。**Phase A–C 全程 R2/R3 保持 VALIDATE_ONLY**；Phase D 极小范围解锁 R2（少数工具 × 少数资源 × 少数 env × 强制人工审批），gate = Phase C 影子证据 + B 组不变量全绿；R3 Phase E 仍双人审批；Hardline 永不执行。

### 0.4 架构总裁定：Worker 提出意图，Action Runner 执行 mutation

LeaseFence 保护"谁有资格向 PostgreSQL 提交结果"，保护不了已发出的外部副作用（epoch=17 的请求在网络中、lease 过期、epoch=18 接管、旧请求抵达 K8s → restart 已发生且收不回）。所以 mutation 走独立链路：

```text
Agent → ToolGateway（现有固定序，不动）
          ├─ R0/R1 → 当前 executor → read result（零改动）
          └─ R2/R3 → 仍不执行 → ActionIntent → Safety Control Plane
                   → Action Runner（独立）→ External mutation
```

---

## 1. R2/R3 目标执行链（AM8 核心状态机）

```text
Agent
  ↓
Action Intent
  ↓
Hardline（先于一切，命中即阻断+审计）
  ↓
Deterministic Policy（四级裁决）
  ↓
Authoritative Resource Resolve          ← 权威解析，不信告警标签（§2.4）
  ↓
Scope Snapshot
  ↓
Risk Classification
  ↓
Guardian / Human Approval
  ↓
SUSPEND（durable suspension，释放 Worker；wall deadline 不冻结，system budget 冻结）
  │
  │ human decision
  ▼
Resume 新 Attempt
  ↓
重新 resolve resource（★世界可能已变）
  ↓
generation / policy / scope / deadline revalidate
  ↓
Acquire Resource Coordinator
  ↓
DB Transaction（★§2.10）：
  ├─ validate ApprovalGrant
  ├─ consume：OperationAuthorization single-use CAS
  ├─ 当前 LeaseFence（执行权栅栏—— executor 资格在这里验，不在审批里）
  ├─ Operation = PREPARED
  ├─ operation_outbox INSERT
  └─ Event INSERT
  ↓ COMMIT
Dispatcher → DISPATCHED → External system
  ├─ ACK → VERIFY → COMPLETED（锁释放）
  └─ timeout → UNKNOWN（★锁保持 BUSY）
                ↓ RECONCILE
                  ├─ VERIFIED（锁释放）
                  ├─ RETRYABLE（重派，锁保持）
                  └─ ESCALATED（锁保持至人工裁决）
```

新增能力全部在 VALIDATE_ONLY 分支之后；Phase C 之前 ⑫ 永远是 shadow/dry-run。

---

## 2. Mutation Safety Plane 详细设计

### 2.0 四级裁决层（R0/R1 零改动）

| 级别 | 判定 | 处置 |
|---|---|---|
| 只读 | R0/R1 | 现有路径直接执行 |
| 低危 | R2 ∩ 低危白名单 ∩ 参数/窗口检查 | Guardian：SAFE→（解封后）执行；否则人工 |
| 高危 | 其余 R2 + R3 | 人工审批 → grant → single-use 消费 → Runner |
| Hardline | 绝对禁止清单 | 先于一切阻断 + 审计 + manifest 裁剪 |

### 2.1 ActionDigest = 授权身份锚

ActionIntent / ApprovalRequest / ApprovalGrant / OperationAuthorization / operations / 审计事件全部绑定 `(action_id, action_digest)`。审批批的是 digest：`restart(payment-api, replicas=3)` 批准后偷换为 `restart(database)` → digest 不同 → 拒绝。

### 2.2 Resource Resolver + Scope Snapshot + 授权扩张（R6）

**信任根论证**：HMAC 验签只能证明"这个 source 发过这条数据"，**不能证明 `env=prod` / resource 标签是正确的授权信息**。授权不依赖不可信业务 payload：

```text
alert.resource_key（仅是 requested resource）
        ↓ Resource Resolver
CMDB / Inventory / IAM
        ↓
resource_uid / canonical_env / canonical_team / canonical_labels / resource_version
```

Scope Authorization 七问：资源是谁 / 哪个 env / 哪个 team / Agent 有权吗 / UID 还是原的吗 / policy 允许吗 / 维护窗口允许吗。判定结果落 scope_snapshot，审批批的是 resolved snapshot，执行前同事务复核。

**授权扩张（authorized_scope_expansion）**：`resource_key ∈ incident.resource_keys` 可能过严——payment-api 告警的根因可能在 upstream gateway，而 gateway 不在原告警资源集内。正确模型：

```text
initial_scope →（调查发现根因在域外）→ authorized_scope_expansion
```

扩张不是 Agent 自行扩大——每次扩张必须重新过 Policy 判定 + 重新审批（新 scope snapshot、新 digest 锚定）。

### 2.3 Guardian 预审

（保留）受限角色只读上下文；封闭三值 SAFE/UNSAFE/UNCERTAIN，UNCERTAIN 转人工；权限单调——SAFE 只对低危白名单生效，无升级放行权；同 trace 计预算；GuardianReviewed 事件审计。

### 2.4 审批面：绑定稳定事实，不绑定瞬时执行身份（R1 —— 本版最大逻辑修正）

**原设计的 bug**：审批表存 `lease_epoch=17`，durable suspension 恢复后新 attempt epoch=18，消费条件 `lease_epoch=18` 永远匹配不上 17 → **审批永远无法消费**。根因：把"执行身份"混进了"审批语义"。

**修正后的分工原则**：

> **Approval 验 Action——"人类是否批准过这个没有发生变化的 Action？"
> LeaseFence 验 Executor——"现在这个执行者有没有资格执行？"**
> 两者不混。

审批绑定**稳定事实**：

```text
approval
├── action_id
├── action_digest
├── run_id                  （run 稳定，attempt 不稳定）
├── observed_generation     （incident 换代即作废）
├── scope_snapshot_hash
├── policy_version
└── expires_at
```

**不绑定**：`owner_worker`、`lease_epoch`、`attempt_id`。恢复后的新 Worker 凭当前 lease_epoch 过 RcaActionGuard / LeaseFence——执行者资格在提交事务的栅栏步骤验（§2.10），不在审批 CAS 里验。

### 2.5 审批三层对象：Request / Grant / Consumption（R8）

**原设计矛盾**：`decision_semantics = session` 与 single-use grant 冲突——第一次消费即 CONSUMED，session 何来复用。修正为三层：

```text
ApprovalRequest（人类决策对象）
    ↓ 决策产生
ApprovalGrant（可复用的授权范围：scope=once/session、参数上限、expires_at）
    ↓ 每次执行
OperationAuthorization（single-use，一次副作用恰好消费一个）
```

例：session grant `G1 = {run=R1, tool=scale, resource=payment-api, max_delta≤2, expires=14:00}`；每次执行产生 `O1、O2…` 各自 single-use。"可复用的授权范围"与"副作用只能消费一次"不再冲突。

### 2.6 双人确认：独立 decisions 表（R7）

JSONB 数组方案否决（两 Reviewer 并发读改写丢更新；"同人不得批两次/两 principal 必须不同"无法用 DB 约束表达）。改为：

```sql
CREATE TABLE approval_decisions (
  decision_id   TEXT PRIMARY KEY,
  approval_id   TEXT NOT NULL,
  approver_id   TEXT NOT NULL,
  approver_role TEXT NOT NULL,
  decision      TEXT NOT NULL,            -- approved / denied
  decided_at    TIMESTAMP DEFAULT NOW(),
  UNIQUE(approval_id, approver_id)        -- 同一人对同一审批只能一条
);
```

事务内校验：两条 approved 决策的 **principal 不同** 且 **role 不同**。**一人同时持有 ONCALL + SECURITY 不算双人**——双人确认要求 two distinct principals，不是两个 role 字符串。

通知失败 fail-closed（5s/15s/45s ×3 → notify_failed 终态）、撤销即终态、过期 300s fail-closed：保留。

### 2.7 生命周期作废矩阵

| 事件 | 结果 | 保障机制 |
|---|---|---|
| 审批期间 run 崩溃 / attempt 更替 | **不影响审批有效性**（★R1：审批不绑 attempt/epoch） | 挂起态持久化，新 attempt 恢复后消费 |
| 审批期间 incident 换代 | 审批作废 | observed_generation 校验 |
| 审批期间 scope/resource version 漂移 | 消费拒绝 | resume 时重新 resolve + snapshot 复核 |
| 审批期间 policy_version 变更 | 审批作废 | policy_version 校验 |
| 通知 3 次全失败 / 超时 300s / 人工撤销 | fail-closed / expired / revoked | 既有机制 |
| R3 缺第二个 distinct principal | 不可消费 | decisions 表校验（§2.6）|

### 2.8 Transactional Operation Outbox（R2 —— 关闭 consume→execute 崩溃窗口）

**洞**：consume 成功 → ApprovalConsumed 落库 → 进程崩溃 → 外部操作从未 dispatch。此时 approval=consumed、operation=不存在：重新审批？直接执行？没有干净答案。

**修**：安全边界整体前移，消费与派发指令同事务原子化：

```sql
BEGIN;
  -- 1. 验 ApprovalGrant（未过期、未撤销、generation/policy/scope 匹配）
  -- 2. OperationAuthorization single-use CAS（status=issued → consumed）
  -- 3. INSERT operations (... status='PREPARED');
  -- 4. INSERT operation_outbox (...);
  -- 5. INSERT rca_event（GrantConsumed + OperationPrepared）
COMMIT;
```

```text
Action Runner Dispatcher：claim outbox → DISPATCHED → external resource
```

即使 COMMIT 后进程立即崩溃，outbox 还在，其他 Dispatcher 可继续。**真正的安全边界**：

```text
LLM → Action Intent → Approval → Durable Operation → Outbox → Dispatcher → External System
```

若消费后 dispatch 长期未发生（PREPARED 悬挂），reconcile 扫描发现并 ESCALATE——不静默丢、不自动重执。

### 2.9 Resource Coordinator：释放受 Operation 状态约束（R3）

**原设计漏洞**：锁 TTL 到期即回收 → Operation A restart 网络超时转 UNKNOWN，锁到期释放 → Operation B 拿锁执行 scale → **A 的 restart 可能仍在进行** → external side-effect zombie（解决了 Worker 僵尸，制造了副作用僵尸）。

**修正**：资源锁生命周期绑定 Operation 状态，不是纯 TTL：

| Operation 状态 | 资源锁 |
|---|---|
| PREPARED / DISPATCHED / ACKNOWLEDGED / **UNKNOWN** / **RECONCILING** | **BUSY——不可授予他人** |
| VERIFIED / COMPLETED / FAILED_CONFIRMED / CANCELLED_BEFORE_DISPATCH | 正常 release |

UNKNOWN 必须先走 RECONCILING 确认真实世界状态，确认前不允许冲突 mutation。锁 TTL 仍然存在，但**过期回收只触发"锁持有者孤儿化 → 驱动 reconcile"，不把锁让给新的 mutation**。

命名澄清：锁内版本列改名 **resource_epoch**（与 run_lease_epoch 区分——一个管"这代调查里你是不是当前执行者"，一个管"这个资源上第几波 mutation"）。

### 2.10 Durable Suspension 与双时钟 deadline（R15）

复用 Redrive：发起审批 → 落检查点 → 释放 scheduler_slot → SUSPENDED_ON_APPROVAL（Reconciler 豁免 AUTO_EXPIRE）→ 回调后补铸 attempt → 恢复 → 重新 resolve → revalidate → 消费。

**deadline 拆两个时钟**（"冻结"只对系统预算成立，对 SLO 不成立——P0 事故审批等 20 分钟不能报 MTTM=3 分钟）：

```text
incident_wall_deadline    永不冻结——真实用户等待的诚实时钟
system_active_budget      审批挂起期间冻结——计算预算时钟
approval_expires_at       独立 300s
```

指标三线分开：`wall_clock_mttm / system_active_time / human_wait_time`。OTel span 分层 blocked_on_user / execution。

### 2.11 Operation 状态机（保留第一轮设计）

只读域三账本 UNKNOWN=诚实终点（重跑无害），不动。mutation 域：PREPARED→DISPATCHED→ACKNOWLEDGED→VERIFIED→COMPLETED；DISPATCHED timeout→UNKNOWN→RECONCILING→{VERIFIED / RETRYABLE / ESCALATED}。网络 timeout ≠ failed；reconcile 依赖 resource_probe 查真实状态。

### 2.12 Sandbox / Runner 三权分立 + TOCTOU 双栅栏

三权分立（审批不持执行权、Runner 不持审批权、容器加固/网络白名单/env 注入）保留。

TOCTOU 双栅栏保留，但哈希链的说法收敛（R9）：

- **准确表述**：哈希链提供 "tamper-evident **under the assumed DB write boundary**"——同库管理员可以删事件 51 并从 52 起全链重算，内部仍然合法。不宣称"密码学可证未被删改"；
- **真增强**（可选增量）：每 N 条事件计算 Merkle checkpoint → 签名 → 写外部不可变存储（WORM），此后 DB 管理员无法悄悄重算历史；
- Runner 校验路径：trusted checkpoint → current segment，**不从 genesis 每次全量验链**（性能）。

---

## 3. Agent Runtime 层：升级，不替换

### 3.1 模型分派：capability 可建，启用由 eval 裁决（R14 —— 最大架构层修正）

- **删除"模型自由分派必须建设并最终常开"的硬要求**。B v2 是设计参考，不是宗教；
- 建设 dispatch-intent **通道**（模型提议 → Supervisor 校验 objective/scope/profile/预算/上限 → 批准才 spawn）作为 capability，feature flag 关闭为默认；
- 开启与否由 eval 数据裁决：若 `RCA +2% 但 cost +40% / loop +15% / unsafe delegation +0.2% / p99 +50%`，**永久保持 DeterministicSupervisor 是完全正确的生产决定**，仍然是优秀的 B 架构；
- 矩阵对应条目（原 D5/L5-8）允许判 ✅：`capability exists + evaluated + policy decided deterministic orchestration is safer`；
- 不变的原则：**模型可以提议调度，但不能拥有调度 authority。**

### 3.2 Progress：事件 → activity / meaningful progress 映射（R5）

三字段：`heartbeat_at`（Worker 活着）/ `last_activity_at`（系统在干活）/ `last_meaningful_progress_at`（incident 真推进）。

| 事件 | Activity | Meaningful progress |
|---|---:|---:|
| LLM token | ✅ | ❌ |
| Tool heartbeat | ✅ | ❌ |
| Lease renewal | ❌ | ❌ |
| 重复 query_logs | ✅ | ❌ |
| 新 evidence | ✅ | ✅ |
| 排除一个 RCA hypothesis | ✅ | ✅ |
| Milestone 前进 | ✅ | ✅ |
| Operation VERIFIED | ✅ | ✅ |

否则模型一直输出"我正在继续分析……"就能永远刷新 progress、永不判 stuck。三层互补：DoomLoopGuard（局部行为异常，五模式双阈值）/ Progress Detector（workflow 推进，LIVE_BUT_STUCK 读 meaningful progress）/ Hard Budget（绝对上限）。

### 3.3 Zombie Reaper：Cancel requested ≠ cancelled（R13）

"发 cancel"不等于"旧 task 已停"，不能立刻 reschedule：

```text
RUNNING → CANCEL_REQUESTED → ABORTING → TERMINATED → 新 attempt
ABORTING 超时 → LOST → epoch++ → takeover
```

task 存在 DISPATCHED / UNKNOWN operation 时，**必须先 reconcile 再 reschedule**。与 "Timeout ≠ failed" 是同一条逻辑：中间态必须被显式穿越，不能跳过。

### 3.4 Replay A 的 source of truth：路线 B（R10）

先回答"谁是事实源"：

- ~~路线 A：Event Sourcing（EventLog=truth，业务表=projection）~~ —— 不为"严格 B v2"做此改造；
- **路线 B（采纳）：Transactional State + Audit Event**——业务表（incident/rca_run/rca_attempt/tool_invocation）是运行时事实源，EventLog 是 durable audit/replay evidence；关键不变式：**`UPDATE state` + `INSERT event` 必须同一 PG 事务**（现有投影/收尾路径已如此）。

Replay A 重定义为：**从事件重建"等价 logical workflow view"，与业务表 snapshot 比对**——验证审计完整性，不承诺"runtime state 只能从 EventLog 产生"。

---

## 4. 其余各层（第二轮修正版）

| 层 | 项 | 处置 | 时相 |
|---|---|---|---|
| L0 | **HMAC 永远验证（R4）** | 验签本身不依赖 Redis；Redis 只承担 nonce 防重放。Redis 故障 → HMAC + timestamp 继续全验 + 本地 nonce cache + **DegradedSecurityEvent**；多实例下本地 cache 无法完全阻止跨实例 replay——登记为已接受风险。**绝不退回 bearer**（故障期安全等级不降） | 并行 |
| L0 | HMAC 签名覆盖面 | `source_id + method + path + timestamp + nonce + SHA256(raw_body) + key_id`——防同一合法 payload 搬到别的 endpoint 重放 | 并行 |
| L0 | injection 扫描 + quarantine | 做 | Phase A |
| L1 | **限流两层拆分（R11）** | ① Edge Abuse Protection：粗粒度限制器（body/connection/per-source abuse）→ 429 + 审计，合法；② Durable Inbox 之后的 Work Admission：P0/P1 超容量 → **DEFERRED（重驱），绝不 429 reject**；P2 → aggregate/defer；P3 → archive-only。**Rate limiting 保护入口，Backpressure 控制工作量** | Phase A |
| L1 | Redis 令牌桶 | 维持观察项（等多实例/负载证据；Edge 层先行用进程内粗限流） | 触发式 |
| L1 | P2 聚合 / P3 只入库 / 投影 DLQ | 做 | Phase A |
| L2 | **incident 状态机修正（R12）** | 唯一硬约束 = "一次一个 active run"（`uq_rca_run_active_incident` 部分唯一索引）；**不以 INVESTIGATING 状态绝对禁止新 run**——run 失败/超时后的重新生成必须允许（现有 RETRY_WAIT/Redrive 路径）；ESCALATED 保留为升级语义 | Phase A |
| L2 | 根因指纹补维 | 做 | Phase A |
| L3 | **Fairness（R16）** | `effective_priority = base_priority + aging(wait_time)`（或 WFQ），防 P2 在 P0 持续时饿死；**抢占三前置**：checkpoint 已提交 ∧ 无 DISPATCHED/UNKNOWN mutation ∧ 不在资源锁临界区；grace 到期不 kill 在途 mutation，转 ABORTING 等待 | Phase B |
| L3 | P0 保留槽位 / 四泳道 / 八信号量 | 保留容量先行 | Phase A/B |
| L8 | 哈希链（口径收敛）/ causation+actor / provenance / OTel / retention+sampling | 做（表述按 §2.12） | Phase A |

---

## 5. 演进路线：E0 + Phase A–E

**优先级清单**：meaningful progress → Eval gate → Scope model → ActionIntent ledger → Resource Coordinator → Operation/Outbox → Approval；HMAC 并行；Redis 触发式。

### Phase E0（最先落）：Eval Harness Skeleton（R17）

在任何机制改造之前：baseline 固化 / invariant framework（§6 A 组断言骨架）/ fault fixture（SIGKILL、双连接 barrier、取消竞态固化入套件）/ candidate-vs-baseline 对比框架。**此后每个 Phase 落地时携带自己的 eval 增量**——杜绝"实现全部完成才发现没有回归检测"。

### Phase A：权限模型不变

R2/R3 继续 VALIDATE_ONLY。做：progress 三字段 + LIVE_BUT_STUCK；A 组不变量全绿进 CI；provenance；OTel；injection 扫描+quarantine；限流两层拆分；DLQ；incident 约束修正（R12）；五模式 stuck；哈希链（收敛口径）；HMAC 并行。
**退出准则**：A 组不变量 CI 门禁全绿；任意 run 决策可从事件+provenance 解释。

### Phase B：搭"假写入"安全链（真实资源零 mutation）

做：ActionIntentLedger；Resource Resolver + scope snapshot + 授权扩张机制；Resource Coordinator（**释放受 Operation 状态约束**，R3 语义从第一天就正确）；Operation Ledger + **Transactional Outbox** + Dispatcher；Runner sandbox。Runner dry-run only。
**退出准则**：全链 dry-run 可从事件重建；cancel/aborting 链与 reconcile 先于 reschedule 的语义验证通过。

### Phase C：Approval Shadow

做：ApprovalRequest / ApprovalGrant / approval_decisions（distinct principals）/ OperationAuthorization 全生命周期；durable suspension + 双时钟 deadline；CONSUMED CAS。**approved → shadow Runner 仍不执行。**
观测：approval correctness / scope correctness（含授权扩张正确率）/ resource conflicts / human latency（human_wait_time 单列）/ FP×FN。
**退出准则**：影子指标达标 + B 组不变量全绿。

### Phase D：R2 极小范围解锁

仅少数工具 × 少数 resource × 少数 env × 人工审批 mandatory（例：restart 一个无状态 staging 服务）。Gate = Phase C 证据。此日 A10 不变量退役，由 scoped mutation invariants 接替。

### Phase E：生产 Limited Autonomy

eval 持续达标后：低危 R2（Guardian SAFE + policy + scope + coordinator）自动执行；R3 双 distinct principal 审批；Hardline 永不执行。模型分派是否开启在此阶段由 eval 数据终裁（§3.1）。

---

## 6. 验收不变量

### A 组：现存可测，E0/A 进 CI

```text
A1  旧 worker 永远不能 commit（LeaseFence 0 行栅栏）
A2  stale generation 永远不能 commit
A3  cancel-vs-finish 只有一个赢家
A4  同 incident 不能两个 active run
A5  重复 alert 不重复铸 run
A6  UNKNOWN 不被自动猜 success
A7  零预算绝不触网
A8  未授权工具绝不进入 executor
A9  dead task 最终一定进入 terminal/lost
A10 R2/R3 永远零副作用（Phase D 解锁日退役）
A11 cancel-requested ≠ cancelled：无 TERMINATED/LOST 前 reschedule 必须被拒绝（R13）
A12 状态更新与事件同事务：任一业务状态变更必伴生同事务事件（R10 路线 B 不变式）
```

### B 组：随机制落地逐条激活

| 时相 | 不变量 |
|---|---|
| Phase B | 授权资源/env 只来自 Resource Resolver canonical identity（alert 标签零授权效力）；scope 扩张必有新审批锚定；UNKNOWN/RECONCILING 期间 resource lock 不让渡（无 external side-effect zombie）；授权事实只来自 rca_event 读路径；hardline 任何路径不可执行；P0 认证告警 durable 落库后永不因容量 reject（仅 DEFERRED） |
| Phase C | 每次副作用恰好消费一个 single-use OperationAuthorization；grant 过期/撤销后 OperationAuthorization 不可签发；审批零绑定瞬时执行身份（epoch/attempt/owner 变更不影响已批 Action 的有效性，executor 资格由栅栏单独验）；双人 = two distinct principals（一人兼两角色不满足）；换代/policy 变更 → 审批作废；挂起不占 slot 且 system_active 冻结、wall clock 不冻结；通知失败 fail-closed |
| Phase D | R3 缺第二 principal 不可执行；解锁范围内每个 mutation 有 PREPARED→VERIFIED 完整账本；resume 后必须重新 resolve resource 且 snapshot 漂移即拒绝 |

---

## 7. 代价与风险（修订版）

| 代价/风险 | 缓解 |
|---|---|
| 三层审批对象（Request/Grant/OperationAuthorization）建模成本 | 换来 session 语义与 single-use 不矛盾；Grant 表即"授权范围"审计面 |
| 资源锁 UNKNOWN 不释放 → 故障时资源长时间 BUSY | 这是正确代价（宁可串行不可并发 mutation）；reconcile SLA + ESCALATED 人工出口；锁等待纳入 deadline 可见 |
| Outbox dispatcher 独立进程/线程 | 复用现有 outbox 模式（notify_outbox 同型）；at-least-once + operation 幂等 |
| WORM/Merkle checkpoint 增量 | 可选增强不阻塞主线；先落"assumed DB write boundary"口径的哈希链 |
| 双人 distinct principals 组织依赖 | R3 fail-safe 不可执行；Phase C 前完成角色矩阵增补 |
| HMAC 无 Redis 时跨实例 replay 残余风险 | 本地 nonce cache + DegradedSecurityEvent + 登记接受；多实例部署时触发 Redis 引入 |
| 解封冲动 | §0.3 纪律条款：Phase D gate = Phase C 影子证据 + B 组全绿，缺一不解锁 |

---

## 8. 一句话总结

> **现有系统是读面完备、写面物理封死的 correctness kernel；向方案 B 的演进以"满足安全不变量"为准绳而非逐字对齐——先把 R2/R3 后面的整条安全执行链搭好并用 shadow 证明再极小解封。链上的五颗钉子：Approval 验 Action、LeaseFence 验 Executor，二者不混；消费与派发指令同事务（Outbox）；资源锁释放受 Operation 状态约束（UNKNOWN 不让渡）；授权资源只信 Resolver 不信告警标签；模型可提议、Supervisor 拥有调度权威且可永久保守。**
