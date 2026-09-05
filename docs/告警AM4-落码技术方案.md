# 告警 AM4 Native 多 Agent 确定性执行链 —— 落码技术方案（执行者用）v1.0

> 定位：AM4 编码的**执行施工图**。任务编号严格对齐 `docs/告警Agent-增量实现任务拆解-v1.md` M4-01~38（已亲自通读原文 §7）；设计依据 = `docs/告警AM4-技术方案.md` v1.0（分层铁律 R1~R5 为头号约束）+ 架构 v1.2 FUT 系列。
> 顺序：用户裁定提前启动（正常依赖 AM3 G2）。本期范围 = M4-01~30；**M4-31~38 待 AM3 落档链（M3-08）就位**。
> 迁移编号：AM3 占 V8/V9，**AM4 自 V10 起**。
> 已落码（2026-09-05 第一批，268 测试绿）：M4-05/06/08/15/21/22 的纯 domain 部分——对应行标【已落码·纯逻辑】，本方案为它们补仓储/接线部分。
> 执行纪律：按编号顺序；只执行与取证；每任务带验收；设计问题回报主会话。

---

## 阶段 A：数据与状态底盘（M4-01~12）

| 任务 | 内容（文件级） | 验收 |
|---|---|---|
| M4-01 | **Task/Run 新旧状态契约双读**：`alert/domain/model/` 新增 `RcaTaskStateV2`/`RcaRunStateV2` 全集枚举（AA-20：含 BLOCKED/SKIPPED/STALE/WAITING_APPROVAL/EXPIRED 等）+ `StateCompat` 双读适配器（旧值→新值映射表，Java 侧先兼容，**不改 DB 约束**）；既有 DagTaskState↔RcaTaskState 接缝在此对齐（第一批遗留） | 旧 fixture 回放测试；状态映射穷举（每个旧值有且仅有一个新值） |
| M4-02 | **状态约束扩容迁移** `V10__am4_state_expansion.sql`：rca_run/rca_task 的 state CHECK 扩为全集（新旧都允许）；**不做数据回填** | 迁移契约 IT：新旧节点都能写入 |
| M4-03 | **状态数据回填作业**：`alert/application/StateBackfillJob`——分批、可重入、进度记录（`UPDATE ... WHERE state=旧值 LIMIT n` 循环 + 进度行）；行数/digest 对账 | 中断重跑 IT；对账一致断言 |
| M4-04 | **rca_task_edge 迁移与仓储** `V11__am4_task_edge.sql`（run_id FK + from/to + dependency_type + uq(from,to) + 自环 CHECK 拒绝）+ `PostgresTaskEdgeRepository` | FK/自环拒绝/重复边幂等 IT |
| M4-05 | DAG 环检测 | 【已落码·纯逻辑】`dag/DagCycleDetector`（10 测试绿）；本任务只补：DagExecutionService 接线时调用点 + 回归 |
| M4-06 | READY/BLOCKED 推进器 | 【已落码·纯逻辑】`dag/DagPromoter`（11 测试绿）；本任务补：`DagExecutionService`（application 层）：完成归约 → 推进后继（单事务） |
| M4-07 | **generation fence**：claim/finish/merge 全比较 observed_generation | 旧 generation 结果变 STALE 且不污染新 Run（IT） |
| M4-08 | RunBudget 账本 | 【已落码·纯逻辑】`budget/RunBudget`（7 测试绿）；本任务补：`budget_ledger` 表（V10 含）+ 仓储 + 并发扣减 IT + 终局保留 + 耗尽测试 |
| M4-09 | IncidentBudget 账本：generation/累计 LLM/Token/费用窗口预算（跨 Run 滚动） | 跨 Run 累计 IT；窗口滚动；耗尽后不派生断言 |
| M4-10 | **统一 rca_event 迁移** `V12__am4_rca_event.sql`：append-only 事件表 + `last_event_seq`（分段 seq 原子分配） | 并发无重复/无倒退 IT；部分索引 |
| M4-11 | **EventAppender**（application）：状态事实同事务写；进度事件独立短事务 | 回滚一致性 IT；重复 event digest 拒绝 |
| M4-12 | 旧事件兼容视图：`rca_agent_event` 只读 VIEW（若历史存在）映射权威表 | 视图与权威表一致断言；写入被拒 IT |

## 阶段 B：工具与证据底盘（M4-13~23）

| 任务 | 内容 | 验收 |
|---|---|---|
| M4-13 | **ToolDefinition 契约**：`domain/tool/ToolDefinition`（name/version/schema/risk/timeout/resultLimit）+ schema_hash | schema hash 稳定；非法定义拒绝 UT |
| M4-14 | **ToolRegistry**：启动期注册 + 冲突检测；**禁止运行时网络下载插件**（ArchUnit 断言无 URLClassLoader 类引用） | 重名/版本冲突/未知工具 UT |
| M4-15 | canonical args + action digest | 【已落码·纯逻辑】`tool/CanonicalJson` + `ActionDigest`（14 测试绿）；本任务只补：与 ToolDefinition 接线 |
| M4-16 | **ToolPolicy R0/R1**：当前只允许读；R2/R3 意图仅记录 VALIDATE_ONLY | 权限矩阵正反 UT（每工具×每风险级） |
| M4-17 | **ToolGateway**（application）：硬 deadline/结果上限/取消；迟到结果不补旧 Snapshot | WireMock timeout/cancel/oversize 测试 |
| M4-18 | 只读调用 Ledger Adapter：`tool_call_ledger` 化用 external_invocation_ledger 形态（PENDING→SUCCESS/FAILED/UNKNOWN；唯一 operation_id） | 网络断开/重复调用/UNKNOWN IT |
| M4-19 | **Evidence 契约与仓储**：provenance/scope/time_range/digest/generation（V12 含 `rca_evidence` 表） | 篡改检测（digest 不符拒绝）；跨 generation 拒绝 IT |
| M4-20 | **EvidenceSnapshot Builder**：冻结排序/裁剪/snapshot_digest；迟到证据不改旧快照 | 同事实同 digest；迟到证据不变更断言 |
| M4-21 | Claim 契约与仓储 | 【已落码·纯逻辑】`claim/Claim`+`ClaimStatus`；本任务补 `rca_claim` 表（V12）+ 仓储；缺引用/冲突 scope/版本错误测试 |
| M4-22 | Claim Reducer | 【已落码·纯逻辑】`claim/ClaimReducer`+`ClaimVerdict`（10 测试绿）；本任务补与仓储/编排接线 |
| M4-23 | **ReportAssembler**：只从已冻结 Snapshot + 已裁决 Claim 组装 | 无证据不产确认根因；PARTIAL/UNRESOLVED 测试 |

## 阶段 C：多 Agent（M4-24~30）

| 任务 | 内容 | 验收 |
|---|---|---|
| M4-24 | **AgentProfile 契约与注册表**：固定 prompt/tool allowlist/budget/schema；不可自由生 Agent | 未注册 Agent 拒绝；digest 稳定 |
| M4-25 | **Planner 结构化输出**：只产受限 DAG 提案（response_format + 文字硬指令双设防，BA-14/P4 教训），不执行工具 | JSON schema/环/未知任务类型测试 |
| M4-26 | **DeterministicSupervisor**：Java 验证/落库/推进 DAG；模型无调度权；固定执行链（PLAN→并行调查→REDUCE→VERIFY≤1→ASSEMBLE→VALIDATE→PUBLISH） | 相同提案相同任务图；崩溃恢复测试 |
| M4-27 | **Metrics Agent**：只做 R0 指标查询 + 产 AgentResult | replay fixture + WireMock/Prometheus 契约测试 |
| M4-28 | **Logs Agent**：只做 R0 日志查询（工具集与 Metrics 隔离） | 同上；隔离断言 |
| M4-29 | **Change Agent**：只读变更记录，无写权限 | 无权限工具调用被控制面拒绝 |
| M4-30 | **Native RCA Agent**：消费结构化黑板（冻结 Snapshot），提出 Claim；不直接发布报告 | 固定 Snapshot 回放可比较（同输入同 claims） |

## 阶段 D：新旧对照（M4-31~38，**待 AM3**）

| 任务 | 内容 | 前置 |
|---|---|---|
| M4-31 | Holmes Baseline Adapter（Holmes 输出→相同 EvidencePackage/Claim 边界） | **M3-08 落档链** |
| M4-32 | REPLAY_MOCK 精确匹配（全字段同才回放，否则 REPLAY_MISS） | M4-17、20 |
| M4-33 | Agent Replay Runner（候选模型调 mock 工具；统计覆盖率与 Token） | M4-30~32 |
| M4-34 | Snapshot Shadow Router（Holmes/Native 读同一 Snapshot 互不影响） | M4-30、31 |
| M4-35 | Online Read Shadow 隔离（仅 R0/R1、独立 slot/限流、REDTEAM 物理禁入） | M4-34 |
| M4-36 | Incident Source Reconciler（低频核对当前告警，只追加 Observation） | M4-09、19 |
| M4-37 | 六类 Reconciler 预算统一件 | M4-18、36 |
| M4-38 | AM4 G2（Native Shadow 全链路不切主） | M4-37 |

---

## 分层铁律执行（头号约束）

- 所有新类归层：纯逻辑 → `domain`（R3 零框架）；编排/事务 → `application`；DB/HTTP/MCP → `infrastructure`；装配 → `config`
- ArchUnit：`ControlArchitectureTest.am4AlertDomainZeroFrameworkDependency` 已守 AM4 新子包；**本期末把覆盖面扩到整个 alert.domain 前必须先处理 BA-22**（EvidencePackageValidator 的 Jackson 遗留——迁移 CanonicalJson 或挪出 domain，随 M4-01~03 批次做）
- 命名冲突纪律：新方法/新类不与既有重名（第一批教训：AFT-A01 同名问题，加 am4 前缀区分）

## DoD

1. M4-01~30 单项验收全过（拆解原文验收列 + 本表）；M4-31~38 登记待 AM3
2. `mvn -q clean verify` 绿且 Failsafe IT 计数非零（含 V10~V12 迁移契约）
3. 分层铁律 ArchUnit 红绿留证；BA-22 关闭（Jackson 遗留处理完毕）
4. 195 真栈：DAG 固定链跑通 + 崩溃恢复 + replay 三态证据（AA-26 契约归档 `docs/测试证据/AM4/`）
5. 台账三件套同步；BA 新缺陷入账

## 修订记录

| 版本 | 变更 |
|---|---|
| v1.0 | 初版：读拆解 M4 原文出具；第一批已落码行标注；迁移编号 V10~V12；BA-22 纳入本期 |
