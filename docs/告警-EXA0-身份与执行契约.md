# EX-A0 · 身份与执行契约冻结（v1.0，2026-09-10）

> 执行者：EX 线单执行者。基准 commit：`aedf1dd`（main）。
> 上游：`docs/告警-执行者ABC-改造技术方案.md` v2.0 §〇 EX-A0、§六 卡模板、§七 测试分层。
> 本契约三方签字面：EX-A1（预算）/ EX-A2（租约与提交栅栏）/ R7（模型执行入口）——A0 冻结后变更需三线评审。

---

## 1. 基准与涉及文件（§六.1）

| 文件 | 方法/位置 | A0 动作 |
|---|---|---|
| `control-app/.../infrastructure/nativeexec/NativeInvestigationExecutor.java` | `execute` / `investigate` / `freezeSnapshot` | 混用消灭：configDigest 不再充当输入身份；时间窗改读 Run 冻结面 |
| `control-app/.../alert/application/agent/NativeRcaAgent.java` | `investigate` | 输入比对输入、快照绑定快照（两参分型） |
| `control-app/.../alert/application/agent/SingleToolEvidenceAgent.java` | `CallContext` / `scopeOf` | `inputSnapshotDigest:String` → `investigationInputDigest:InvestigationInputDigest` |
| `control-app/.../alert/application/tool/ToolGateway.java` | `ToolInvocation` | 同上改名换型 |
| `control-app/.../alert/application/tool/ReplayToolGateway.java` | 回放键计算 | 随 envelope 改名换型（wire 键不动） |
| `control-app/.../alert/domain/tool/ActionEnvelope.java` / `ActionDigest.java` | envelope 组件 | 组件改名换型；**canonical JSON 键 `inputSnapshotDigest` 冻结不动**（见 §3.4） |
| `control-app/.../alert/domain/evidence/EvidenceSnapshotBuilder.java` | `SnapshotInput` / `digest` | `configDigest` 保留 String（影子侧身份 `am4-shadow-trigger:executor-v1` 非 hex64，换型会砍掉影子路径合法输入）；返回值换型 `EvidenceSnapshotDigest` |
| `control-app/.../alert/application/Am4ShadowTrigger.java` | `investigate` | 影子路径输入身份改用本语义（原传快照 digest 亦属混用） |
| `control-app/.../alert/application/IncidentProjector.java` | `castRunAndTask`（铸点①） | 铸时冻结三件套（输入 digest+窗口）随 `insertRouted` 落列 |
| `control-app/.../alert/application/RcaRunOrchestrator.java` | `castRunAndTask`（铸点②，RERUN） | 同上 |
| `control-app/.../alert/domain/repository/RcaRunRepository.java` | `RoutingView` / `insertRouted` | 读视图加三列；写面加 `insertRouted(run, routing, inputs)` |
| `control-app/.../infrastructure/persistence/PostgresRcaRunRepository.java` | `insertRouted` / `findRoutingById` | 列级读写 |
| `db/migration/V36__exa0_investigation_identity.sql` | — | 列级迁移（本文 §5） |

## 2. 前置输入契约（§六.2）

- 铸点输入：`Incident`（incidentKey/episodeGeneration/episodeStartedAt/lastFiringStartsAt）+ 铸造时刻 `now`。窗口政策：`[now-600s, now]`（沿用执行器 `RANGE_WINDOW_SECS=600` 语义，锚点由执行时刻改为铸造时刻）。
- `InvestigationInputs`（domain identity 面）：incidentId/incidentKey/episodeGeneration/windowStart/windowEnd/serviceScope/queryParams。`inputDigest()` = `InternalCanonicalJsonV1.sha256`，canonical 形态 schemaVersion=`investigation-input.v1`（F23 身份格式锚：canonical JSON、key 字典序、数值 BigDecimal 归一；**长度前缀**沿用 InternalCanonicalJsonV1 字符串转义形——新增身份一律走本件，不另立格式）。
- 三 digest 全部 sha256 小写十六进制 64 位；类型构造即校验 `^[0-9a-f]{64}$`。

## 3. 持久身份契约（§六.5，F04/F14/F23/动作身份）

### 3.1 三 digest 分列（列级持久化，各司其职）

| digest | 语义 | 持久列 | 计算点 |
|---|---|---|---|
| `config_digest` | 配置版本（路由 bundle） | `rca_run.config_digest`（V25 已有） | 路由器 |
| `investigation_input_digest` | 调查输入身份（episode+窗口+范围+查询参数） | `rca_run.investigation_input_digest`（V36 新增） | 铸点（Mint 时冻结） |
| `evidence_snapshot_digest` | 输出证据集身份 | `rca_evidence_snapshot.snapshot_digest`（V16 已有） | 冻结快照时 |

### 3.2 冻结时间窗（F14）

- `rca_run.window_start/window_end`（V36 新增，可空）——Run 创建时冻结，执行期只读。
- 执行器禁止再取"执行时最近十分钟"：窗口非空列必用冻结值；空列（存量行/部署缝隙）回退旧行为并 WARN 留痕（旧行为零变化的兼容面，新铸 run 一律有值）。
- "现在是否恢复"是新的显式动作（R7 落卡），**不得**通过移动原调查窗口实现。

### 3.3 动作身份三元组（attempt/call_seq/action_seq）

| 身份 | 语义 | 持久面 | 接线卡 |
|---|---|---|---|
| `attempt` | 物理执行尝试（一次 worker 驱动） | `rca_attempt`（task_id+attempt_no 唯一） | F16（EX-A4a）：DAG 任务驱动也铸 attempt 行，attemptId 不再随机现铸 |
| `call_seq` | 同 attempt 内**物理请求**单调序号 | `rca_tool_invocation.call_seq`（V15 已有，UNIQUE(run,task,attempt,call_seq,tool) 幂等键一半） | 已接线 |
| `action_seq` | 同 (run,task) 内**逻辑动作**序号 | `rca_tool_invocation.action_seq`（V36 新增契约列，可空） | EX-A4a F16 接线 |

**逻辑动作 vs 物理请求（写入契约的区分）**：
- 同逻辑动作读**已完成回执**：复用原身份（同 action_seq+call_seq），零触网、零计费；
- **新物理请求**（重试/重发）：必须新 call_seq（同 attempt 内）或新 attempt（跨驱动），单独预留、单独计费（EX-A1 接 `RunBudgetGate`）；
- UNKNOWN（请求已发出、结果未知）：身份保留、保守占用预算待对账（EX-A1 P1-02），不免费重发。

### 3.4 wire 格式冻结（F23 一次定好）

- 新身份 canonical 形态一律 `InternalCanonicalJsonV1`（internal-v1）；schemaVersion 字段进 canonical 形态（如 `investigation-input.v1`），换格式必须换版本号。
- `ActionDigest` canonical JSON 键 `inputSnapshotDigest` **冻结不改名**（改名=digest 全量漂移=replay 夹具报废，收益为零）；其值语义自本卡起=调查输入身份（Java 组件名已改 `investigationInputDigest`）。若未来必须改名，走 `canonicalizationVersion` 升版，另开评审。

## 4. 预算与结果提交接口契约（§六.2/四事件，A1/A2/R7 三方签字面）

### 4.1 预算预留键（EX-A1 直接沿用，零改动）

```java
ReservationKey(UUID runId, UUID taskId, UUID attemptId, long callSeq, BudgetKind budgetKind)
// UNIQUE(run_id, task_id, attempt_id, call_seq, budget_kind)；REPORT 用 nil-UUID 哨兵
```

- attemptId 语义 = `rca_attempt.id`（F16 接线后即持久行 id，不再是随机现铸值）；
- R7 复用本键，**全系统唯一预算所有者 = `RunBudgetGate.call(key, estimate, remote, usageExtractor)`**；R7 经 `ActionBudgetContext`（R7 自持接口）取键构造，不得二次扣账。

### 4.2 提交事务边界（EX-A2 栅栏的契约面）

- 结果业务准入 + 任务状态迁移 + 事件提交 = **同一事务**，事务内锁定所有权行（owner/epoch/generation/Run 状态校验），影响行数=0 即失去提交权（P1-04）；
- 远端迟到响应只可作审计/对账资料，不得进入有效证据快照；
- 取消线性化点：取消事务成功后不得取得新动作发送资格（EX-A2 落码）。

## 5. 迁移与回滚（§六.6，V36）

```sql
-- V36__exa0_investigation_identity.sql
alter table rca_run
    add column investigation_input_digest char(64),
    add column window_start timestamptz,
    add column window_end   timestamptz;
alter table rca_run add constraint ck_rca_run_window
    check ((window_start is null and window_end is null) or
           (window_start is not null and window_end is not null and window_start <= window_end));
alter table rca_tool_invocation add column action_seq bigint;
alter table rca_tool_invocation add constraint ck_rca_tool_invocation_action_seq
    check (action_seq is null or action_seq >= 0);
```

- 全列可空：存量行（部署缝隙中已铸 run）不回填（无可信回填源），执行面回退旧行为（§3.2）；
- 回滚步骤：V36 仅加列，回滚 = drop 四列与两约束（`flyway undo` 不可用时手工执行）；应用侧对缺列行有回退路径，先滚应用后滚库；
- 编号纪律：rebase 时以下一可用号为准替换 V36。

## 6. 状态转换与拒绝原因（§六.3）

执行器身份面 fail-closed 沿用并扩一行：

| 场景 | 行为 |
|---|---|
| `configDigest` 缺失 | `CONFIG_DIGEST_MISSING`（不变） |
| 冻结窗口列缺失（存量行） | 回退执行时窗口 + WARN（兼容面，仅存量） |
| 冻结 `investigation_input_digest` 缺失（存量行） | CallContext 输入身份为空 → 证据 scope 无输入键（行为同旧注记语义） |
| 三 digest 类型错用 | **编译期拒绝**（三个独立类型，无公共接口、无隐式互转） |

## 7. 测试分层（§七 7.2，缺层视同未完成）

| 层 | 环境 | 内容 |
|---|---|---|
| L0 | 开发机 `mvn test` | ①三 digest 类型隔离与语义分野（新 `InvestigationIdentityTest`）；②`InvestigationInputs` digest 稳定性（同输入同 digest/窗口变 digest 变）；③`HarnessAuditCharacterizationTest#annotatedEvidenceWithInputDigestIsDroppedAgainstOutputSnapshotDigest` 翻转为契约断言（输入比对输入，匹配即采纳）；④执行器冻结窗口（冻结列在场=必用；缺列=回退+WARN）；⑤既有 L0 全绿（含 ActionDigest/Replay 面键名冻结证明） |
| L1 | 开发机/构建环境 `mvn verify`（真 PG） | 具名 IT `ExA0InvestigationIdentityIT`：V36 列级契约（insertRouted 带身份落列→RoutingView 读回三值分列、冻结窗口可读）、存量行（裸 insert）回退语义、迁移契约钉（Am6MigrationContractTest 同形）；全部具名 IT 零跳过 |
| L2 | 195 真机（涉及部署） | 部署后旧 Live E2E 全链零回归：webhook→incident→NATIVE run→报告→发布链行为不变；rca_run 新行三列在场且执行日志无"冻结窗口缺失"WARN |

## 8. 完成证据（§六.7）

- L0/L1 日志：`m6-ev/exa0-*.log`；
- 本卡证据目录：`docs/告警-EXA0-身份与执行契约.md`（本文）+ 迁移文件 + 测试文件路径；
- BUGLOG 登记：本卡无新缺陷（摸底发现的"注记证据必被丢弃"为 F04 混用的既有后果，随本卡翻转修复，不另立 BA——若评审判应立，补 BA-64）。
