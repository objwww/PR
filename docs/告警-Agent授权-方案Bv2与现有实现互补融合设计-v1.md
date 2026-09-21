# 告警 Agent 授权：方案 B v2 与现有实现互补融合设计 v1

日期：2026-09-15
状态：设计评审稿（G1 门禁前）
前置阅读：`docs/架构设计-告警Agent-v1.2.md`、`docs/告警-EXA2-租约与提交栅栏.md`、`docs/告警-OR04身份角色端点矩阵-v1.md`

---

## 0. 融合总原则

> **以方案 B v2 为准绳，现有实现为手段。逐条对照以《方案Bv2严格对照-符合性矩阵-v1》为验收清单；任何偏离必须在矩阵 §11 偏离登记表签字冻结，不允许实现里悄悄不一样。**

三条裁决规则（服从准绳原则）：

1. **符合性矩阵优先**：本设计中"保留现有""放弃 B v2"的所有结论，凡与矩阵 🔀 项冲突的，以矩阵登记表裁定结果为准。
2. **概念翻译而非概念移植**：B v2 的每个"一等公民"映射到现有术语与表结构（如不新建 `conversations` 表，对应登记为矩阵 D3）。
3. **偏离须论证**：现有设计比 B v2 更保守的偏离（如 DeterministicSupervisor，矩阵 D5）也须登记批准，不得默认放行。

---

## 1. 概念对照表（B v2 → 现有实现）

| B v2 概念 | 现有对应物 | 裁决 |
|---|---|---|
| Conversation（一事故一单写者） | incident + `uq_rca_run_active_incident` 部分唯一索引 + generation | ✅ 等价，保留现有 |
| fencing token | `lease_epoch` + `LeaseFence` 提交栅栏 | ✅ 等价，保留现有（B v2 的 lease_epoch 是抄回来的，本来就是我们的） |
| owner token | `lease_owner` + 续租后所有权复核 | ✅ 等价 |
| Task Ledger | `rca_task` / `rca_attempt`（lease 三元组 + 六态含 STALE） | ✅ 等价，缺 `last_progress_at`（见 §3.4） |
| EventLog sequencer | `rca_run.last_event_seq` 行锁同事务推进 | ✅ 等价，缺哈希链（见 §3.6） |
| Operation UNKNOWN / reconcile | `rca_tool_invocation` UNKNOWN + `markHangingInvocationsUnknown` | ✅ 等价 |
| effectively-once | 逻辑幂等键 + LeaseFence + 事件 event_id 幂等 | ✅ 等价 |
| Deadline 传播 | task `deadline_at` → ToolGateway 取 min → AUTO_EXPIRE | ✅ 等价 |
| Cancellation Tree | ExecutionControl + InFlightToolCancels + 心跳探针 | ✅ 等价 |
| 审批（approval_requests / CONSUMED CAS） | 无（AM5+ 设计态，v1.2 §11.3） | ⭐ 增量，见 §3.1 |
| Durable Suspension（审批挂起） | 无直接对应，但 `IncidentWaitingRedrive`（WAITING_CAPABILITY/DEFERRED 补铸 run）是同构先例 | ⭐ 增量，复用 Redrive 路径，见 §3.2 |
| Resource Coordinator | 无（R2/R3 不执行故暂无冲突面） | ⭐ 增量，审批前置依赖，见 §3.3 |
| 操作范围校验（env/标签/维护窗口） | 无 | ⭐ 增量，见 §3.5 |
| Goal Contract / 多维 drift | 无（DoomLoopGuard 只覆盖循环维度） | ⭐ 增量，降级为结构化校验，见 §3.8 |
| 入口令牌桶 | 无（DeferredPolicy 软背压 + bulkhead） | ⭐ 增量，见 §3.7 |
| Priority Lane / P0 保留容量 | task priority 排序（无容量隔离） | ⭐ 增量，见 §3.7 |
| Decision Provenance | 散落在 run/事件/配置，无统一结构 | ⭐ 增量，见 §3.6 |
| Eval Plane（fault injection 套件 / Replay） | 一次性演练（SIGKILL、两连接 barrier）未固化 | ⭐ 增量，见 §3.9 |
| Redis 租约 / Redlock | 无 | ❌ 放弃（PG 行锁路线已验证更简） |
| 事件溯源替代 TOCTOU 签名 | LeaseFence + 修订锚 CAS | ❌ 放弃（审批面用 v1.2 §11.3 的 action_digest + observed_generation + single-use grant，与现有栅栏同一血统） |
| 主 Agent 自由分派 sub-agent | DeterministicSupervisor 模型无调度权 | ❌ 放弃（保留更保守方） |

---

## 2. 融合后的分层架构

B v2 的 9 层骨架保留，但每一层标注**现有组件**，增量用 ★ 标注：

```
L0 入口安全    MachineBearerAuthnFilter + 路径×角色矩阵 + AlertIntakeService schema 校验
              ★ prompt injection 扫描器（隔离区）
L1 Ingress    alert_inbox 六态 + 退避 + DB UNIQUE 去重
              ★ 入口令牌桶（进程内 + DB 窗口兜底）
L2 关联       incidentKey + UNIQUE + ON CONFLICT 回读 + episode 水印（不动）
              ★ 投影层死信队列（重试耗尽后不落 DLQ 的洞）
L3 调度       scheduler_slot + priority 排序 + deadline 传播
              ★ P0 保留槽位（slot 行 scope 维度预留）
L4 Runtime    lease_epoch + LeaseFence + 双租约回收 + 取消线性化（不动）
              ★ rca_attempt.last_progress_at + LIVE_BUT_STUCK reaper
              ★ 审批挂起 durable suspension（复用 IncidentWaitingRedrive）
L5 Agent      DeterministicSupervisor + BoundedLlmRoleRunner + DoomLoopGuard + RunBudgetGate（不动）
              ★ Goal Contract（结构化 drift 校验，挂在 Supervisor 阶段转换点）
L6 Safety     ToolRisk R0-R3 + ToolPolicy 双闸（不动）
              ★ approval_requests + CONSUMED CAS + 通知重试 fail-closed
              ★ Resource Coordinator（resource_operation_lock 表）
              ★ 操作范围校验（scope snapshot）
L7 Operation  rca_tool_invocation 幂等 + UNKNOWN + reconcile（不动）
              R2/R3 从 VALIDATE_ONLY 转为 审批后执行（随审批面解锁）
L8 Event      rca_event append-only + seq + digest 幂等（不动）
              ★ prev_hash/event_hash 哈希链
              ★ Decision Provenance 统一字段族 + OTel 接线
L9 Eval       现有评测模块 + HOLDOUT
              ★ fault injection 套件固化 + Replay A/B + 发布门禁
```

---

## 3. 增量设计（9 项，按落地优先级）

### 3.1 ★ 审批面（最大增量，建议立项 AM8）

**嵌入方式**：不新建平行体系，落到 v1.2 §11.3 已有目标态——控制面提出 → 策略判定 → 人工审批 → 独立 Action Runner 执行。

```sql
CREATE TABLE approval_requests (
  approval_id        TEXT PRIMARY KEY,
  incident_id        TEXT NOT NULL,
  run_id             TEXT NOT NULL,
  operation_id       TEXT NOT NULL,           -- 对应 rca_tool_invocation 逻辑幂等键
  tool_name          TEXT NOT NULL,
  action_digest      TEXT NOT NULL,           -- 复用 canonical ActionDigest
  resource_key       TEXT NOT NULL,
  observed_generation BIGINT NOT NULL,        -- 审批锚定 incident generation
  status             TEXT NOT NULL,           -- pending/approved/denied/expired/revoked/consumed/notify_failed
  decision_semantics TEXT,                    -- once 只此一次 / session / deny
  required_role      TEXT NOT NULL,           -- 按风险级路由：R2→on-call，R3→双人
  approvals          JSONB DEFAULT '[]',      -- 双人确认存两条决策
  notify_status      TEXT,
  notify_attempts    INT DEFAULT 0,
  requested_at       TIMESTAMP DEFAULT NOW(),
  expires_at         TIMESTAMP NOT NULL,      -- 默认 300s，超时 fail-closed
  consumed_at        TIMESTAMP,
  lease_epoch        BIGINT,                  -- 消费时校验 run 仍持租约
  version            INT DEFAULT 0,
  UNIQUE(run_id, operation_id)
);
```

**关键机制（与现有血统对齐）**：

- **合并**：同 `(run_id, tool_name, action_digest)` 的重复请求复用同一行（单 run 单写者，天然无跨 Worker 合并问题；B v2 的 Leader/Follower 在我们架构里退化为 UNIQUE 冲突回读）。
- **CONSUMED CAS**：消费走条件 UPDATE（status=approved AND version 匹配），与 LeaseFence 同一模式；**消费时必须同时校验 `lease_epoch` 与 `observed_generation`**——审批期间 incident 换代（重燃）则审批作废。
- **通知失败**：重试 3 次（5s/15s/45s，挂 notify_outbox 现有退避机），全失败 → `notify_failed` → fail-closed，**不留 pending**。
- **双人确认（R3）**：`approvals` 数组需两条不同 `decided_by`，CAS 消费前检查。
- **撤销**：未消费前可撤销，撤销即终态。
- **Action Runner 分离**：审批服务不持执行权限、Runner 不持审批权限（v1.2 §11.3 原话）。Runner 执行凭 single-use grant = `(operation_id, action_digest, observed_generation, consumed approval)`，执行前过 LeaseFence。

#### 3.1.0 前置：风险四级裁决（对齐"只读/低危/高危/绝对禁止"语义）

现有 ToolRisk 是**危险描述轴**（R0 只读 / R1 敏感读 / R2 副作用 / R3 危险不可逆），处置只有两档（executable() → R0/R1 执行，R2/R3 一律 VALIDATE_ONLY 零执行）。目标分级是**处置动作轴**（每级对应一条处置路径），两者对账：

| 目标分级 | ToolRisk 对应 | 现状 | 裁决层处置 |
|---|---|---|---|
| 只读 → 直接执行 | R0/R1 | ✅ 已对齐 | 直接执行（现有路径不变） |
| 低危 → Guardian 预审 | 无对应级 | ❌ 缺整个中间层 | R2 且在本地低危白名单 + 参数/窗口检查通过 → Guardian 预审（同 trace_id，计入预算账本）：SAFE 执行 / 否则转人工 |
| 高危 → 人工审批 | R2 其余 + R3 | ❌ 有标签无路径（VALIDATE_ONLY 永不执行） | approval_requests + CONSUMED CAS + 超时 fail-closed |
| 绝对禁止 → 直接阻断 | 无对应级 | ❌ 无 hardline 清单（AM8 解锁 R3 后此洞变真实风险） | 命中 hardline 清单（工具名 / 工具+参数模式）→ 先于一切阻断 + 安全审计事件；同时从 manifest 裁剪 |

**实现要点**：

- 在 ToolGateway 固定顺序中插入裁决层：`注册表 → 策略鉴权 → ★风险裁决 → 范围校验(§3.5) → schema 校验 → deadline 执行`。
- ToolRisk 保留作描述底座（不动已验证代码）；裁决层输出四级处置枚举。
- 低危白名单与 hardline 清单均从**本地配置**读（与 ToolRisk 不信任 MCP annotation 同一原则）。
- 分级粒度从"按工具静态标级"升级为"按（工具+参数+env+维护窗口）动态裁决"——复用维护窗口表（§3.5）与 Goal Contract 的 `forbidden` 字段（§3.8）。
- Guardian 预审是新的受限角色：只读上下文 + 输出 SAFE/UNSAFE/UNCERTAIN 封闭三值，复用 DelegationReceipt 回执准入。

**审批解锁后 R2/R3 的行为变化**：

| 阶段 | R2/R3 行为 |
|---|---|
| 现状 | VALIDATE_ONLY 只记意图零执行 |
| AM8 后 | 意图 → 范围校验（§3.5）→ 审批 → CONSUMED → Resource Coordinator（§3.3）→ 执行 → 回执事件 |

### 3.2 ★ Durable Suspension（审批挂起不占 Worker）

**原则**：等待人类不是计算，是挂起。审批等 10 分钟不能占着 `scheduler_slot`。

**嵌入方式（复用现有 Redrive，不引入 WAITING_RUN 状态机的混乱）**：

```
Run 发起审批
  → 写 approval_requests(pending) + 意图账本 WAITING_APPROVAL 注记
  → run 落检查点（复用 V47 rca_primary_checkpoint）
  → 释放 scheduler_slot，run 状态记 SUSPENDED_ON_APPROVAL（新枚举，不碰 RcaStateContract 既有六态的语义，是"终态之外的挂起态"，由 RunReconciler 豁免 AUTO_EXPIRE）
审批回调（批准/拒绝/超时）
  → 写审批决策事件
  → 走 IncidentWaitingRedrive 同构路径补铸 attempt（WAITING_CAPABILITY/DEFERRED 已有先例）
  → 新 attempt 从检查点恢复，消费审批 → 执行
```

**收益**：Worker 吞吐不被审批拖住；审批等待不计入 live-but-stuck；human_wait 与 system_active 分开统计。

### 3.3 ★ Resource Coordinator（跨 run 资源串行）

**为什么现在就要**：审批解锁 R2/R3 后，两个 incident 的 run 可能同时操作同一资源（如 `deployment/payment-prod`）。Conversation 串行 ≠ 资源串行。

**嵌入方式（复用 scheduler_slot 模式，不引入新中间件）**：

```sql
CREATE TABLE resource_operation_lock (
  resource_key   TEXT PRIMARY KEY,
  run_id         TEXT NOT NULL,
  operation_id   TEXT NOT NULL,
  lease_owner    TEXT NOT NULL,
  lease_until    TIMESTAMP NOT NULL,
  lease_epoch    BIGINT NOT NULL,
  acquired_at    TIMESTAMP DEFAULT NOW()
);
```

- 获取：`INSERT ... ON CONFLICT (resource_key) DO NOTHING RETURNING`，冲突则排队（工具等待纳入 deadline 传播）。
- 回收：与 slot/task 相同的过期回收 + epoch 条件 UPDATE（`reclaimExpired` 四条件同语句模式）。
- 生命周期：锁随 operation 终态释放；Worker 崩溃由双租约回收兜底。

### 3.4 ★ last_progress_at + LIVE_BUT_STUCK Reaper

**缺口**：现有 RunReconciler 能收死 run（heartbeat 断），但"心跳正常、无真实进展"的僵尸 run 只能靠 DoomLoopGuard 在 run 内自杀，控制面观测不到。

**改动**（极小）：

```sql
ALTER TABLE rca_attempt ADD COLUMN last_progress_at TIMESTAMP;
```

- **Progress 事件**（写入即刷新该列）：LLM token 流、工具结果返回、sub-agent 回执、审批决策、状态迁移。**Lease 续租不算 progress**。
- **RunReconciler 增加一档判定**：

| heartbeat_age | progress_age | 判定 | 动作 |
|---|---|---|---|
| < 20s | < 5min | HEALTHY | 无 |
| < 20s | > 5min | **LIVE_BUT_STUCK** | 发取消（走现有 ExecutionControl）+ 审计事件 |
| > TTL | — | LOST | 现有回收路径 |

### 3.5 ★ 操作范围校验（scope snapshot）

**嵌入点**：ToolGateway 固定顺序中，策略鉴权之后、deadline 执行之前：

```
注册表 → 策略鉴权 → ★范围校验 → schema 校验 → deadline 执行
```

**校验项**（对 R2/R3 强制，R0/R1 可选）：

- **env 限制**：incident 的 env 标签（如 prod）决定 run 只能操作同 env 资源；
- **资源作用域**：`resource_key` 必须在 incident 关联资源集内（`incident.resource_keys` 已有）；
- **维护窗口**：高危操作查窗口表（新表 `maintenance_window(resource_key, allowed_ops, window_start, window_end)`）；
- **快照一致性**：范围判定结果作为 scope snapshot 写入意图账本，执行前复核 snapshot 未变（与 LeaseFence 同事务）。

### 3.6 ★ 事件哈希链 + Decision Provenance

**哈希链**（低成本）：`rca_event` 加两列：

```sql
ALTER TABLE rca_event ADD COLUMN prev_hash TEXT, ADD COLUMN event_hash TEXT;
```

seq 已由 `last_event_seq` 行锁串行，加链只需在同事务内读上一条 hash 再插入。验链作业（每日）重算比对。**意义**：从"DB 授权面防改"升级为"密码学可证未被删改"。

**Provenance**：统一 provenance 块写入关键事件 payload（不动表结构）：

```json
"provenance": {
  "model_provider": "...", "model_id": "...", "model_version": "...",
  "prompt_version": "...", "prompt_hash": "...",
  "agent_build_sha": "...", "policy_version": "...",
  "tool_name": "...", "tool_schema_hash": "...",
  "parent_event_id": 0, "causation_id": 0
}
```

**OTel 接线**：TracedTasks 已备好，接 deploy/alert/otelcol。纪律：**incident_id/run_id/attempt_id 作 attribute，trace_id/span_id 由 OTel 自己生成**，不拿业务 ID 伪造 trace_id；默认 metadata-only，敏感内容走 EventPayloadSanitizer 既有白名单。

### 3.7 ★ 入口令牌桶 + P0 保留容量

**令牌桶**（不引入 Redis）：

- 进程内令牌桶（control-app 当前单实例部署，够用）per source_id；
- 多实例时退化为 DB 窗口计数（单行 UPDATE 计数 + 窗口重置，与预算账本同一模式）；
- 位置：L0 认证之后、inbox 落库之前。超额 → 429 + 审计事件（不静默丢）。

**P0 保留容量**：`scheduler_slot` 表加 `reserved_for` 列：

```sql
ALTER TABLE scheduler_slot ADD COLUMN reserved_for TEXT; -- NULL=通用，'P0'=仅 critical
```

默认 2 槽中 1 槽 reserved_for='P0'。P2 洪峰最多占满通用槽，P0 永远有座。抢占不做（复杂度高、收益低），保留容量已够。

### 3.8 ★ Goal Contract（降级实现）

**不照搬 B v2 的"每步 LLM 自问"**（烧钱且不可靠），降级为**结构化校验，挂在 DeterministicSupervisor 的阶段转换点**：

```yaml
# incident 创建时由投影层生成，落 run 检查点
goal_contract:
  incident_key: "alertname=HighErrorRate,service=payment-api"
  resource_scope: ["deployment/payment-api"]   # = incident.resource_keys
  env: prod
  forbidden_ops: ["database_delete", "cross_region_change"]
  success_signal: "error_rate < 1% for 5m"     # VERIFY 阶段的判定依据
```

**校验点**：

- PLAN→调查：调查计划的资源是否都在 resource_scope 内（Scope drift）；
- REDUCE→VERIFY：结论引用的证据是否都在证据台账内且未过期（Evidence drift）；
- VERIFY：success_signal 用只读工具实际查询判定，不让模型自评（State drift）；
- 每次工具调用：resource_key ∈ scope（与 §3.5 共用校验器）。

Goal drift / Policy drift / Model drift 通过 provenance 版本字段在事件层观测（§3.6），不做运行时拦截。

### 3.9 ★ Eval Plane 固化

**把一次性演练变成回归门禁**：

- **Fault injection 套件**（挂在现有 IT 框架，真 PG）：

| 故障 | 现状 | 固化 |
|---|---|---|
| Worker SIGKILL | ✅ AM1 演练过 | 进套件 |
| 两连接并发接管 | ✅ barrier 实证过 | 进套件 |
| 取消 vs 完成竞态 | ✅ 实证过 | 进套件 |
| LLM 429 / timeout / 畸形 tool call | 部分（错误两族） | ★ 补齐 |
| 工具 HTTP 成功但响应丢失（→UNKNOWN） | 逻辑有 | ★ 端到端用例 |
| 审批回调重复 / 乱序 | 审批未实现 | 随 AM8 配套 |
| 两 run 抢同一资源 | 锁未实现 | 随 §3.3 配套 |
| compaction 中崩溃 | 默认关 | 开启前必备 |

- **Replay A（状态重放）**：rca_event reducer 重建 incident/run 状态，验证"事件可重建一切"——每个版本进 CI。
- **Replay B（换模型复评）**：eval 域历史 incident + 工具快照 + simulator，换 model/prompt 复跑，出 delta 报告——发布门禁。
- **北极星指标报红根因**（模型工具调用可靠性）正是 L2/L8 eval 缺口的代价，套件化后此类问题在发布前拦截。

---

## 4. 明确不做的（B v2 有、我们不要）

| B v2 机制 | 不做原因 |
|---|---|
| Redis 租约 / Redlock | PG 行锁路线已验证，少一个故障面 |
| 事件溯源替代 TOCTOU 签名 | LeaseFence + 修订锚 CAS 已覆盖同风险面；审批面用 single-use grant（同血统） |
| conversations 表 | rca_run + generation + checkpoint 语义等价 |
| 主 Agent 自由分派 sub-agent | DeterministicSupervisor 更保守，保留 |
| LLM 每步自问 Goal Contract | 降级为结构化校验（§3.8） |
| K8s Lease API | Docker Compose 单栈，PG 行锁语义等价 |
| 独立指标管道 | 指标从 rca_event 派生 + Prometheus 已有 |

---

## 5. 落地顺序与里程碑建议

| 序 | 项 | 依赖 | 建议批次 | 改动量 |
|---|---|---|---|---|
| 1 | last_progress_at + LIVE_BUT_STUCK（§3.4） | 无 | 快批（搭 WC 批便车） | 1 列 + Reconciler 一档 |
| 2 | 入口令牌桶 + P0 保留槽位（§3.7） | 无 | 快批 | 1 列 + 过滤器 |
| 3 | 事件哈希链（§3.6 前半） | 无 | 快批（迁移 + 写入路径 + 验链作业） | 2 列 + 同事务写 |
| 4 | 操作范围校验（§3.5） | 无（R2/R3 未解锁也可先落校验器） | AM8 前置批 | ToolGateway 一环 + 窗口表 |
| 5 | Resource Coordinator（§3.3） | 无 | AM8 前置批 | 1 表 + 获取/回收 |
| 6 | **审批面 AM8**（§3.1 + §3.2） | 4、5 | AM8 正式立项（方案先行→G1→G2） | 最大增量 |
| 7 | Provenance 统一 + OTel 接线（§3.6 后半） | 无 | AM8 并行批 | payload 约定 + collector |
| 8 | Eval 套件固化（§3.9） | 随各项配套 | AM8 验收门禁 | IT 套件 + Replay |
| 9 | Goal Contract（§3.8） | 无 | AM9 候选 | 检查点字段 + 4 个校验点 |

**门禁对齐**：AM8 走 milestone-workflow 标准流程——本设计评审（G0）→ 技术方案 v1（G1）→ 最小实现 → L0 全量 + 195 真 PG IT → 部署验证（G2）。

**验收不变量**（AM8 必须全部真 PG 实证）：

```
P0 认证告警永不静默丢失
同一 operation 不产生重复不可逆副作用
过期 lease_epoch 不允许更新 durable state
审批只消费一次（CONSUMED CAS）
无审批的高危操作永远执行不了
审批期间 incident 换代则审批作废
挂起 run 不占 scheduler_slot
资源冲突永远串行
run 崩溃/换代后状态可从事件重建
哈希链每日验链通过
```

---

## 6. 一句话总结

> **B v2 对我们的真正增量不是租约和幂等（这些我们更成熟），而是审批面、范围校验、progress 观测、入口限流、可证明性（哈希链+provenance）和评测门禁六件事；融合方式是把它们各自嵌入现有同构机制（Redrive、scheduler_slot、LeaseFence、ToolGateway 固定序），而不是新建平行体系。**
