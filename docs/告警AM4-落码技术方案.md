# 告警 AM4 Native 多 Agent 确定性执行链 —— 落码技术方案（执行者用）v1.1

> 定位：AM4 编码的**执行施工图**。任务编号严格对齐 `docs/告警Agent-增量实现任务拆解-v1.md` M4-01~38；设计依据 = `docs/告警AM4-技术方案.md` **v1.1**（迁移编号/状态全集/DoD 已按评审修正）+ 架构 v1.2 FUT 系列。
> v1.1 修正（评审 7 P0 全采纳）：① 第一批已落码纯函数降级为"设计预研/备料"，**不计 M4 任务完成登记**；② 迁移重排：V8=AM1 dag 预留（已存在，含 rca_task_edge）/V9=AM3 eval/notify/V10=AM3 eval_run/**AM4 自 V11 起且一迁移一任务**；③ M4-04 改为复用补强不重建表；④ 状态全集对齐冻结表（WAITING_APPROVAL 属 AM5）；⑤ DoD 移除 Replay/Shadow/Holmes 隔离；⑥ Logs/Change 本期只做 replay fixture；⑦ 第一批代码四项修正随本方案 G0 批次执行。
> 顺序：M4-01 依赖 M3-30（拆解原文）。**本期编码性质 = 预研/备料**，正式完成登记待 M3-30 后按序进行。

---

## 批次 G：既有纯逻辑修正（先于一切，对应评审第一批代码问题）

| 批次 | 内容 | 验收 |
|---|---|---|
| GX-1 | ClaimReducer：分组键 = claimKey+normalizedScope+timeRange+observedGeneration+snapshotDigest；单来源单状态 ≠ CORROBORATED（basis=SINGLE_SOURCE 不算确认）；同源重复只算一票；TRUE/FALSE 各 ≥2 独立来源 → NEEDS_REVIEW（禁枚举顺序优先） | 矩阵穷举全绿 |
| GX-2 | Claim 校验：拒绝空 claimKey/空 evidenceRefs/负 generation/空 source | 正反 UT |
| GX-3 | RunBudget：+SUBTASK 维度；TIME 改固定 deadline 语义；Math.addExact 防溢出；javadoc 注明终局正确性靠 DB 原子预留（后续批次） | 穷举 UT |
| GX-4 | CanonicalJson 更名 `InternalCanonicalJsonV1`（不声称 RFC 8785 兼容）；ActionDigest 改结构化 envelope 整体 canonicalize（toolNamespace/toolName/toolVersion/schemaVersion/canonicalArgs/timeRange/inputSnapshotDigest/canonicalizationVersion="internal-v1"），禁 `\|` 手拼 | 字段序无关/任一字段变 digest 变 UT |
| GX-5 | DagPromoter 终局收敛：REQUIRED 前驱终态失败 → 后继确定性收敛 SKIPPED（带原因）而非永久 BLOCKED；同节点对 REQUIRED+OPTIONAL 冲突边 → PlanCompiler 输入校验拒绝（IAE） | 收敛矩阵 + 冲突边 UT |

## 阶段 A：数据与状态底盘（M4-01~12）

| 任务 | 迁移/文件 | 内容 | 验收 |
|---|---|---|---|
| M4-01 | 无迁移 | Task/Run 新旧状态契约双读（Java 适配器，不改 DB 约束）；状态全集 = Task 六态+**BLOCKED/RUNNING/SKIPPED/FAILED_TERMINAL/STALE**、Run +**REPORTING/PARTIAL/EXPIRED**（**无 WAITING_APPROVAL**） | 旧 fixture 回放、状态映射穷举 |
| M4-02 | **V11** | 状态约束扩容（DB 同时允许新旧，不回填） | 新旧节点写入契约 IT |
| M4-03 | 无迁移 | 状态数据回填作业（分批/可重入/进度/对账） | 中断重跑、行数/digest 对账 |
| M4-04 | 无新建表 | **复用 V8 的 rca_task_edge**：补强约束（**from/to 同属一个 run_id——组合外键或触发器**，跨 run 连边拒绝）+ 仓储 | FK/自环/重复边/跨 run 拒绝 IT |
| M4-05 | — | 环检测 | 【备料已落码】接线 + 回归 |
| M4-06 | — | READY/BLOCKED 推进器（含 GX-5 终局收敛） | 【备料已落码 + GX-5 修正】DagExecutionService 接线；并发前驱/可选前驱失败 IT |
| M4-07 | — | generation fence（claim/finish/merge 全比较 observed_generation） | 旧 generation 结果 STALE 不污染新 Run IT |
| M4-08 | **V12** | RunBudget 账本（step/subtask/tool/evidence/time 硬预算 + Token 预留接口 + finalization reserve；DB 原子预留→提交/释放） | 并发扣减、终局保留、耗尽 IT |
| M4-09 | V12 同任务 | IncidentBudget（跨 Run 窗口滚动） | 跨 Run 累计、窗口滚动、耗尽不派生 IT |
| M4-10 | **V13** | 统一 rca_event（append-only + last_event_seq 分段原子分配） | 并发无重复/无倒退 IT |
| M4-11 | — | EventAppender（状态事实同事务；进度事件独立短事务） | 回滚一致性、重复 digest 拒绝 IT |
| M4-12 | — | 旧事件兼容只读视图 | 视图一致断言、写入被拒 IT |

## 阶段 B：工具与证据底盘（M4-13~23）

| 任务 | 迁移 | 内容 | 验收 |
|---|---|---|---|
| M4-13 | — | ToolDefinition 契约（name/version/schema/risk/timeout/resultLimit + schema_hash） | hash 稳定、非法定义拒绝 UT |
| M4-14 | — | ToolRegistry（启动期注册+冲突检测；禁运行时下载插件——ArchUnit 断言） | 重名/版本冲突/未知工具 UT |
| M4-15 | — | canonical args + action digest | 【备料已落码 + GX-4 修正】接线 ToolDefinition |
| M4-16 | — | ToolPolicy R0/R1（R2/R3 意图仅 VALIDATE_ONLY 记录） | 权限矩阵正反 UT |
| M4-17 | — | ToolGateway（硬 deadline/结果上限/取消；迟到不补旧 Snapshot） | WireMock timeout/cancel/oversize |
| M4-18 | **V14** | 只读调用账本（PENDING→SUCCESS/FAILED/UNKNOWN，唯一 operation_id） | 断网/重复/UNKNOWN IT |
| M4-19 | **V15** | Evidence 契约与仓储（provenance/scope/time_range/digest/generation） | 篡改检测、跨 generation 拒绝 IT |
| M4-20 | V15 同任务 | EvidenceSnapshot Builder（冻结排序/裁剪/snapshot_digest） | 同事实同 digest；迟到证据不改旧快照 |
| M4-21 | **V16** | Claim 仓储（rca_claim 表） | 【备料已落码 + GX-2 修正】缺引用/冲突 scope/版本错误测试 |
| M4-22 | — | Claim Reducer | 【备料已落码 + GX-1 修正】接线 |
| M4-23 | — | ReportAssembler（只从冻结 Snapshot + 已裁决 Claim 组装） | 无证据不产根因；PARTIAL/UNRESOLVED UT |

## 阶段 C：多 Agent（M4-24~30）

| 任务 | 内容 | 验收 |
|---|---|---|
| M4-24 | AgentProfile 契约与注册表（固定 prompt/tool allowlist/budget/schema） | 未注册拒绝；digest 稳定 |
| M4-25 | Planner 结构化输出（受限 DAG 提案，双设防） | JSON schema/环/未知类型/冲突边拒绝 UT |
| M4-26 | DeterministicSupervisor（Java 验证/落库/推进；模型无调度权） | 相同提案相同图；恢复测试 |
| M4-27 | Metrics Agent（R0 指标查询 + AgentResult） | replay fixture + WireMock/Prometheus 契约 |
| M4-28 | Logs Agent——**数据源限制（评审 P0-7）**：当前无冻结的实时日志源，**只做 replay fixture，不得宣称 Live E2E**；Live 需先冻结数据源清单+只读凭证+ToolDefinition+部署契约 | 同上 + 与 Metrics 工具集隔离 |
| M4-29 | Change Agent——同上（无冻结变更记录源，replay fixture 限定） | 无权限工具调用被拒 |
| M4-30 | Native RCA Agent（消费冻结黑板产 Claim，不直接发报告） | 固定 Snapshot 回放可比较 |

## 阶段 D：新旧对照（M4-31~38，**待 AM3，不在本期任何验收内**）

M4-31 Holmes Adapter（依赖 M3-08）/ M4-32 REPLAY_MOCK / M4-33 Replay Runner / M4-34 Shadow Router / M4-35 Shadow 隔离 / M4-36 Source Reconciler / M4-37 Reconciler 预算统一件 / M4-38 AM4 G2——任务边界见拆解原文，本期不动。

---

## E2E-M4 业务端到端套件（评审增补，进 §阶段 C 验收）

E2E-M4-00 无故障不制造 RCA/候选通知；01 F1 同 generation 证据 + 命中 GT + 可回查；02 F2 证据缺失只 PARTIAL；03 F3 未对账必 UNKNOWN/PARTIAL；04 Claim 冲突 NEEDS_REVIEW 保留双方证据；05 generation 交替全 STALE 不污染；06 四杀点 SIGKILL 无重复执行/无预算透支/无永久 BLOCKED；07 prompt injection 被 Gateway 拒绝；08/09 待 M4-32~38。证据包必含：scenario_id/run/generation/DAG digest/snapshot digest/tool ledger/事件序列/Claim/Verdict/报告 digest/预算对账 + "Candidate 未发布"DB 断言。

## DoD（v1.1 修正版）

1. GX-1~5 修正全绿 + M4-01~30 单项验收全过（拆解原文验收列）
2. `mvn -q clean verify` 绿且 Failsafe IT 计数非零（V11~V16 迁移契约）
3. 分层铁律 ArchUnit 红绿留证；BA-22 关闭
4. 195 真栈：DAG 固定链 + 崩溃恢复 + E2E-M4-00~07 证据（AA-26 契约）
5. **不含** Replay/Shadow/Holmes 隔离（属 M4-32~38）；本期完成登记仍受 M3-30 前置约束
6. 台账三件套同步

## 修订记录

| 版本 | 变更 |
|---|---|
| v1.0 | 初版（迁移编号 V10~V12，后被评审推翻） |
| v1.1 | 评审 7 P0 全采纳：迁移重排（V8 已存在实锤，AM4=V11~V16 一迁移一任务）；M4-04 复用补强 + 同 run 连边约束；状态全集对齐冻结表；DoD 移除 Replay/Shadow/Holmes 隔离；Logs/Change 数据源限制；新增 GX-1~5 第一批修正批次 + E2E-M4 套件 |
