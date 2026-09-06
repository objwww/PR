# 告警 AM4 Native 多 Agent 确定性执行链 —— 落码技术方案（执行者用）v1.2

> 定位：AM4 编码的**执行施工图**。任务编号严格对齐 `docs/告警Agent-增量实现任务拆解-v1.md` M4-01~38；设计依据 = `docs/告警AM4-技术方案.md` **v1.2**（含 §6.1 源码级参照与修正清单，E-16）+ 架构 v1.2 FUT 系列。
> **门禁状态（v1.2 更新）**：M3-30 已达成（2026-09-06 八链全绿收口，origin/main `574c01f`）→ M4-01 前置依赖解除；AM4 技术方案 **G1 已通过（2026-09-06 用户批准）**。**本期编码 = 正式执行**，不再按"预研/备料"口径。第一批已落码纯函数（M4-05/06/08/15/21/22 + GX-1~5）需在对应任务行做接线与 IT 补齐后方计完成。
> v1.1 修正（评审 7 P0 全采纳，继续有效）：① M4-04 复用补强不重建表（V8 已含 rca_task_edge）；② 状态全集对齐冻结表（WAITING_APPROVAL 属 AM5）；③ DoD 移除 Replay/Shadow/Holmes 隔离；④ Logs/Change 本期只做 replay fixture。
> **迁移编号（v1.2 修正，P-45 裁定落账）**：AM3 落码实际占用至 **V11**（M3-15 新增 eval_app 只读授权），v1.1 的"AM4 自 V11 起"作废。重排冻结：**V12=AM4 状态扩容（M4-02）、V13=Run/IncidentBudget（M4-08/09）、V14=rca_event（M4-10）、V15=工具调用账本（M4-18）、V16=Evidence/Snapshot（M4-19/20）、V17=Claim（M4-21）**——一个迁移编号只承载一个任务的 DDL，已发布迁移不得追加。

---

## 批次 G：既有纯逻辑修正（已完成，登记备查）

| 批次 | 内容 | 验收 |
|---|---|---|
| GX-1~5 | ClaimReducer 分组键/平局裁决、Claim 校验、RunBudget 补强、ActionDigest envelope + CanonicalJson 更名 InternalCanonicalJsonV1、DagPromoter 终局收敛+冲突边拒绝 | ✅ 已落码并复核（292 tests 全绿，origin/main `3be79d5`） |

## 阶段 A：数据与状态底盘（M4-01~12）

| 任务 | 迁移/文件 | 内容 | 验收 |
|---|---|---|---|
| M4-01 | 无迁移 | Task/Run 新旧状态契约双读（Java 适配器，不改 DB 约束）；状态全集 = Task 六态+**BLOCKED/RUNNING/SKIPPED/FAILED_TERMINAL/STALE**、Run +**REPORTING/PARTIAL/EXPIRED**（**无 WAITING_APPROVAL**） | 旧 fixture 回放、状态映射穷举 |
| M4-02 | **V12** | 状态约束扩容（DB 同时允许新旧，不回填） | 新旧节点写入契约 IT |
| M4-03 | 无迁移 | 状态数据回填作业（分批/可重入/进度/对账） | 中断重跑、行数/digest 对账 |
| M4-04 | 无新建表 | **复用 V8 的 rca_task_edge**：补强约束（**from/to 同属一个 run_id——组合外键或触发器**，跨 run 连边拒绝）+ 仓储 | FK/自环/重复边/跨 run 拒绝 IT |
| M4-05 | — | 环检测 | 【备料已落码】接线 + 回归 |
| M4-06 | — | READY/BLOCKED 推进器（含 GX-5 终局收敛） | 【备料已落码 + GX-5 修正】DagExecutionService 接线；并发前驱/可选前驱失败 IT |
| M4-07 | — | generation fence（claim/finish/merge 全比较 observed_generation） | 旧 generation 结果 STALE 不污染新 Run IT |
| M4-08 | **V13** | RunBudget 账本（step/subtask/tool/evidence/time 硬预算）。**v1.2 加固（E-16 §4）**：① Token 维 = **预留-实扣-平账三段式**（reserve 按"输入实计+输出最坏值"原子预增、超预算预留时即拒 → reconcile/release；取消按 input floor 结算不退零；escrowId 随机 UUID 不复用请求 ID 防重放——litellm `budget_reservation.py:176-302` + Helicone `Wallet.ts:483-531` 双先例）；② 并发扣减 = PG `SELECT FOR UPDATE` 行锁事务（Bucket4j 同栈先例），扣减 API 返回 `BudgetProbe{allowed,consumed,remaining,retryAfterMs}`；③ **doom_loop 熔断并入 step 维**（同工具+相同 canonical args digest 连续 3 次→确定性终止；禁 JSON.stringify 键序比对——OpenCode `processor.ts:354-381` 先例与坑）；④ usage 权威顺序：服务端 usage 权威、本地估值仅事前封顶、缺失记 UNMATCHED 不伪造零；⑤ **耗尽路径零 LLM 调用**（smolagents/CrewAI/LangChain 反例实证） | 并发扣减、终局保留、耗尽 IT；耗尽零 LLM 调用断言 |
| M4-09 | V13 同任务 | IncidentBudget（跨 Run 窗口滚动）。**v1.2 加固**：① "耗尽不派生"落地为**新 Run 创建先过 admission 预留**；② **预算存储不可用默认 fail-closed**（litellm 默认 fail-open 为反面教训）；③ 多窗口（24h+7d）独立账本（OpenMeter grant 分项烧减参照） | 跨 Run 累计、窗口滚动、耗尽不派生 IT；存储故障 fail-closed IT |
| M4-10 | **V14** | 统一 rca_event（append-only + last_event_seq 分段原子分配）。**v1.2 定型（E-16 §2）**：照 eventuous PG 最小骨架——(run_id, seq) 唯一约束 + `SELECT FOR UPDATE` expected-version 校验 + 唯一冲突**显式报错**（禁静默 no-op）；global seq 用 identity，**仅作展示/分页游标，对拍断言一律用 (run_id, seq)**（回滚留洞，eventuous tombstone 实证） | 并发无重复/无倒退 IT；冲突显式报错 IT |
| M4-11 | — | EventAppender（状态事实同事务；进度事件独立短事务） | 回滚一致性、重复 digest 拒绝 IT |
| M4-12 | — | 旧事件兼容只读视图 | 视图一致断言、写入被拒 IT |

## 阶段 B：工具与证据底盘（M4-13~23）

| 任务 | 迁移 | 内容 | 验收 |
|---|---|---|---|
| M4-13 | — | ToolDefinition 契约（name/version/schema/risk/timeout/resultLimit + schema_hash）。**v1.2 明确**：version/schemaHash 无开源先例、属差异化加固保留；schema_hash 输入 = canonical JSON；risk 缺省从严分级（MCP annotations destructiveHint 默认 true 同思想） | hash 稳定、非法定义拒绝 UT |
| M4-14 | — | ToolRegistry（启动期注册+冲突检测；禁运行时下载插件——ArchUnit 断言）。**v1.2 升硬要求**：重名/版本冲突**fail-fast 拒绝启动**（langgraph/SK-java/mcp-gateway 三家静默覆盖反面教材）；注册期纳入 prerequisites 式可用性检查（HolmesGPT `tools.py:730-737`） | 重名/版本冲突**启动期拒绝** UT/装配测试；未知工具 UT |
| M4-15 | — | canonical args + action digest。【备料已落码 + GX-4 修正】**v1.2 流程定死**：先 schema 校验并剔除未声明字段 → 再 canonical → 再哈希 | 接线 ToolDefinition；噪声字段不变/范围变化必变 UT |
| M4-16 | — | ToolPolicy R0/R1（R2/R3 意图仅 VALIDATE_ONLY 记录）。**v1.2 两处修正（INV-AM4-8）**：① **空策略/无配置 = 拒绝启动或显式确认**（default-allow 三陷阱实证：agentgateway `rbac.rs:53-54`、mcp-gateway `capabilitites.go:367-368`、kagent 空列表全量）；② **被拒工具从下发 LLM 的工具清单直接裁掉**（Gemini CLI deny 即剔除），优于调用时才拒 | 权限矩阵正反 UT + 空策略拒绝启动 UT + 下发清单裁剪断言 |
| M4-17 | — | ToolGateway（硬 deadline/结果上限/取消；迟到不补旧 Snapshot）。**v1.2 语义定型**：① 超时/异常默认**转为模型可见的错误结果**（不抛异常栈——六方互证，openai-agents error_as_result `tool.py:2135-2166`）；② **审批挂起是独立状态、绝不落入 FAILED**（langgraph interrupt `tool_node.py:982`、HolmesGPT APPROVAL_REQUIRED、MCP input_required 三方先例） | WireMock timeout/cancel/oversize；超时转错误结果断言；挂起态≠FAILED 断言 |
| M4-18 | **V15** | 只读调用账本（PENDING→SUCCESS/FAILED/UNKNOWN，唯一 operation_id）。**v1.2 加固**：FAILED 子分类照抄 crewai 六分类+retryable（`tool_failure.py:35-54`）；审批 token 可选加固（HolmesGPT HMAC `approval_tokens.py:52-56`） | 断网/重复/UNKNOWN IT；失败分类穷举 UT |
| M4-19 | **V16** | Evidence 契约与仓储。**v1.2 定型（E-16 §2）**：契约 = in-toto Statement 三段式（_type 版本 URI + subject digest map + predicateType）+ provenance = GUAC 三元组 {collector, origin, documentRef}；**digest 纪律 = rekor 全链**：digest 对 canonical 字节算、**库存规范化原始字节**、读出原样不重算、验证走"重算 canonical→比对"（禁对象重序列化）；跨代拒绝 = 版本 URI 不匹配即拒收 | 篡改检测、跨 generation 拒绝 IT |
| M4-20 | V16 同任务 | EvidenceSnapshot Builder。**v1.2 修正**：**snapshot_digest 显式列**（成员 digest 排序 canonical 后再哈希——Iceberg snapshotId 是随机值，不得依赖 id 列）；冻结 = 新 snapshot 不可变+parent 链+CAS 提交；篡改检测 L1=行 digest+聚合 CRC（Delta）为基线，L2 prev_digest 行链（immudb 最小移植）**列为可选增强、由评审按威胁模型拍板**，L3 Merkle/签名不做 | 同事实同 digest；迟到证据不改旧快照 |
| M4-21 | **V17** | Claim 仓储（rca_claim 表）。【备料已落码 + GX-2 修正】**v1.2 加固（E-16 §3）**：双标识正交——**claim_fingerprint 判同一断言、claim_hash 判内容等价** → 合并/覆盖/新建三分支（Keep `alert_deduplicator.py:61-114`）；裁决/时间戳字段不进哈希（ignore_fields 惯例）；**判定事件连 none 也落库**；反向修正三家评测库危险默认：空证据引用=拒绝、说不清=NEEDS_REVIEW、空 claim 集=UNRESOLVED | 缺引用/冲突 scope/版本错误测试；三分支矩阵 UT |
| M4-22 | — | Claim Reducer。【备料已落码 + GX-1 修正】**v1.2 定型**：表驱动不引规则引擎；裁决出口**封闭枚举** CONFIRMED/SUPPORTED/REFUTED/SUPERSEDED/NEEDS_REVIEW（"无事发生"也是一等出口，alertmanager ReasonDoNotNotify）；指纹 = 字段**排序后**哈希；Claim.verdict + justification_refs 两张表，证据撤销→级联降级（drools TMS 抄思想不引库）；两阶段形态"先按 fingerprint 分桶再桶内裁决"（dedupe） | 接线；冲突/支持/反证/NEEDS_REVIEW 矩阵 |
| M4-23 | — | ReportAssembler（只从冻结 Snapshot + 已裁决 Claim 组装）。**v1.2 依据修正**：确定/推测分节**形式**可抄 HolmesGPT，但分节依据必须来自 M4-22 裁决状态机而非 LLM 自述（HolmesGPT 无裁决层 = 幻觉敞口源码反证 `tool_calling_llm.py:1147-1161`） | 无证据不产根因；PARTIAL/UNRESOLVED UT |

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

## 阶段 D：新旧对照（M4-31~38，**AM3 G2 已过（2026-09-06），已解锁——按序衔接 M4-30 之后执行**）

M4-31 Holmes Adapter（依赖 M3-08）/ M4-32 REPLAY_MOCK / M4-33 Replay Runner / M4-34 Shadow Router / M4-35 Shadow 隔离 / M4-36 Source Reconciler / M4-37 Reconciler 预算统一件 / M4-38 AM4 G2——任务边界见拆解原文。（v1.2 备注：M4-37 施工时按 E-16 §4 三态分流——预算耗尽→终态、速率/临时→退避、可选降级续跑；积压年龄维无开源先例，自实现挂窗口起点。）

---

## E2E-M4 业务端到端套件（评审增补，进 §阶段 C 验收）

E2E-M4-00 无故障不制造 RCA/候选通知；01 F1 同 generation 证据 + 命中 GT + 可回查；02 F2 证据缺失只 PARTIAL；03 F3 未对账必 UNKNOWN/PARTIAL；04 Claim 冲突 NEEDS_REVIEW 保留双方证据；05 generation 交替全 STALE 不污染；06 四杀点 SIGKILL 无重复执行/无预算透支/无永久 BLOCKED；07 prompt injection 被 Gateway 拒绝；08/09 待 M4-32~38。证据包必含：scenario_id/run/generation/DAG digest/snapshot digest/tool ledger/事件序列/Claim/Verdict/报告 digest/预算对账 + "Candidate 未发布"DB 断言。

## DoD（v1.2 修正版）

1. M4-01~30 单项验收全过（拆解原文验收列）；GX-1~5 已绿登记备查
2. `mvn -q clean verify` 绿且 Failsafe IT 计数非零（**V12~V17** 迁移契约）
3. 分层铁律 ArchUnit 红绿留证；BA-22 关闭
4. **INV-AM4-8/9 红绿留证（v1.2 增）**：空策略/预算存储故障 fail-closed；预算耗尽路径零 LLM 调用断言；注册重名 fail-fast；被拒工具不进下发清单
5. 195 真栈：DAG 固定链 + 崩溃恢复 + E2E-M4-00~07 证据（AA-26 契约）
6. 本期（M4-01~30）验收**不含** Replay/Shadow/Holmes 隔离（属 M4-32~38，已解锁，在阶段 D 验收）
7. 台账三件套同步

## 修订记录

| 版本 | 变更 |
|---|---|
| v1.0 | 初版（迁移编号 V10~V12，后被评审推翻） |
| v1.1 | 评审 7 P0 全采纳：迁移重排（V8 已存在实锤，AM4=V11~V16 一迁移一任务）；M4-04 复用补强 + 同 run 连边约束；状态全集对齐冻结表；DoD 移除 Replay/Shadow/Holmes 隔离；Logs/Change 数据源限制；新增 GX-1~5 第一批修正批次 + E2E-M4 套件 |
| v1.2 | ① G1 通过（2026-09-06 用户批准）+ M3-30 已达成 → 本期编码转正式执行；② **迁移顺延一格（P-45 落账）：AM3 实际占用至 V11，AM4 = V12~V17**（V12 状态扩容/V13 预算/V14 rca_event/V15 工具账本/V16 Evidence+Snapshot/V17 Claim）；③ E-16 源码级调研修正点落任务行：M4-08 Token 三段式预留+PG 行锁+doom_loop 熔断+耗尽零 LLM、M4-09 admission 预留+fail-closed、M4-10 eventuous 骨架+(run_id,seq) 对拍主键、M4-14 冲突 fail-fast、M4-16 空策略拒绝启动+被拒工具裁清单、M4-17 超时转模型可见结果+挂起态独立、M4-18 失败六分类、M4-19 in-toto 三段式+rekor digest 纪律、M4-20 snapshot_digest 显式列、M4-21 双哈希三分支+none 落库、M4-22 封闭枚举出口+justification_refs、M4-23 分节依据来自裁决状态机；④ DoD 增 INV-AM4-8/9 红绿项 |
