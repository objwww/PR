# 告警 AM4 Native 多 Agent 确定性执行链 —— 落码技术方案（执行者用）v1.3

> 定位：AM4 编码的**执行施工图**。任务编号严格对齐 `docs/告警Agent-增量实现任务拆解-v1.md` M4-01~38；设计依据 = `docs/告警AM4-技术方案.md` **v1.3**（G1 评审退回修订版，全部终裁定在正文 §6，本方案与其逐行对齐）+ 架构 v1.2 FUT 系列。
> **门禁状态（v1.3 修正，评审 P0-1）**：M3-30 已达成（2026-09-06，origin/main `574c01f`）→ M4-01 前置依赖解除；**AM3 G2 已通过（用户 2026-09-06 确认）** → M4-31~38 解锁；**AM4 G1 未签署（2026-09-06 评审退回，v1.3 复审中）** → **本期编码性质 = 预研/备料，不得宣称正式开工**，任务完成登记自 G1 签署后起算。第一批已落码纯函数（M4-05/06/08/15/21/22 + GX-1~5）同样按备料口径，接线与 IT 补齐后方计完成。
> v1.1 修正（评审 7 P0 全采纳，继续有效）：① M4-04 复用补强不重建表（V8 已含 rca_task_edge）；② 状态全集对齐冻结表（WAITING_APPROVAL 属 AM5）；③ Logs/Change 本期只做 replay fixture。
> **迁移编号（正式裁定，评审 P0-2）**：AM3 已实际占用 V9/V10/V11；**AM4 = V12~V17**：V12=状态扩容（M4-02）、V13=Run/IncidentBudget（M4-08/09）、V14=rca_event（M4-10）、V15=工具调用账本（M4-18）、V16=Evidence/Snapshot（M4-19/20）、V17=Claim（M4-21）——一迁移一任务，已发布迁移不得追加。

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
| M4-08 | **V13** | RunBudget 账本（step/subtask/tool/evidence/time 硬预算）。**三段式预留（评审补强幂等语义）**：① reserve 按保守估值（输入实计+输出最坏值；调用前 token 只有保守估算，不声称精确）原子预增，超预算预留时即拒；② **幂等业务键 (run_id, task_id, attempt_id, call_seq, budget_kind) 唯一约束——重试读取同一笔 reservation，不得重新预留**；③ reconcile/release：完成按服务端 usage 实扣平账；**发送前取消→释放预留；发送后取消→进 PROVISIONAL/UNMATCHED 等对账，不立即按 input floor 平账**；④ PG 行锁短事务（读-判-扣-写），**网络调用期间绝不持 SELECT FOR UPDATE**；扣减返回 `BudgetProbe{allowed,consumed,remaining,retryAfterMs}`；⑤ **最终报告生成保留单独预算**；⑥ usage 权威顺序：服务端权威、本地估值仅事前封顶、缺失记 UNMATCHED 不伪造零；⑦ **耗尽路径零 LLM 调用**（INV-AM4-9）；⑧ DoomLoopGuard 见下（独立组件，不并入 step counter） | 并发扣减、终局保留、耗尽 IT；**评审增补 IT：100 并发预留永不超扣 / 同业务键重试不重复预留 / 预留后进程崩溃可对账 / 发送前后取消不同结算路径 / usage 缺失不按零释放 / 预算存储不可用零 LLM 调用 / 锁持有边界断言** |
| M4-09 | V13 同任务 | IncidentBudget（跨 Run 窗口滚动）：① 新 Run 创建先过 **admission 预留**（耗尽不派生）；② **预算存储不可用默认 fail-closed**；③ 多窗口（24h+7d）独立账本 | 跨 Run 累计、窗口滚动、耗尽不派生 IT；存储故障 fail-closed IT |
| M4-10 | **V14** | 统一 rca_event（append-only）。**评审裁定：不引入 global seq**——`rca_run.last_event_seq` 与事件插入**同一短事务**（run 行 FOR UPDATE → seq+1 → insert）；`UNIQUE(run_id, seq)` + `UNIQUE(run_id, event_id)`；**同一 event_id+digest 重放幂等；同一 event_id 但 digest 不同 = 冲突显式报错**（禁静默 no-op） | 并发追加每 run seq 连续单调 IT；**状态事务回滚事件必回滚 IT**；**同 event 重放幂等/digest 异才冲突 IT**；冲突显式报错 IT |
| M4-11 | — | EventAppender（状态事实同事务；进度事件独立短事务） | 回滚一致性、重复 digest 拒绝 IT |
| M4-12 | — | 旧事件兼容只读视图 | 视图一致断言、写入被拒 IT |

## 阶段 B：工具与证据底盘（M4-13~23）

| 任务 | 迁移 | 内容 | 验收 |
|---|---|---|---|
| M4-13 | — | ToolDefinition 契约（name/version/schema/risk/timeout/resultLimit + schema_hash）：schema_hash 输入 = canonical JSON；**schema 默认 additionalProperties=false**；risk 缺省从严；**风险等级只来自本地注册表，不信外部 MCP annotation** | hash 稳定、非法定义拒绝 UT |
| M4-14 | — | ToolRegistry（启动期注册+冲突检测；禁运行时下载插件——ArchUnit 断言）。**启动/运行检查分离（评审裁定）**：启动期硬失败 = 空策略/无配置（非测试环境）、本地 Schema/配置/**Java 方法签名与 Schema 一致性**校验失败（更名，非密码学签名）、重名/版本冲突（fail-fast，禁静默覆盖）；运行期降级不致死 = Prometheus/日志源暂不可达只影响 readiness/能力可用状态 | 重名/版本冲突**启动期拒绝** UT/装配测试；**同名同版本不同 schema_hash 启动失败 UT**；**外部源暂不可达不杀应用（readiness 降级）测试**；未知工具 UT |
| M4-15 | — | canonical args + action digest。【备料已落码 + GX-4 修正】**评审收紧**：schema 校验（additionalProperties=false，**未声明字段直接拒绝**——禁静默裁字段防撞 digest）→ 仅版本化规则列入的非语义元数据可排除 → canonicalize → digest | 接线 ToolDefinition；噪声字段不变/范围变化必变 UT；**未声明参数直接拒绝 UT** |
| M4-16 | — | ToolPolicy R0/R1（R2/R3 意图仅 VALIDATE_ONLY/PROPOSED/VALIDATED_INTENT 记录，**无审批态**）。**评审收紧**：① 空策略**硬失败**（非测试环境，无"或显式确认"模糊分支）；② 被拒工具**从下发 LLM 清单删除**；③ **Gateway 执行时仍二次鉴权**（双闸）；④ 伪造 readOnly annotation 不影响本地权限判定 | 权限矩阵正反 UT + 空策略硬失败 UT + 下发清单裁剪断言 + **伪造 annotation 仍被拒 UT** + 执行二次鉴权断言 |
| M4-17 | — | ToolGateway（硬 deadline/结果上限/取消；迟到不补旧 Snapshot）。**错误两族（评审裁定）**：模型可见族（NO_DATA/RATE_LIMITED/可重试 TIMEOUT/临时远端故障——结构化+脱敏，无堆栈/凭据/内部地址）；控制面终止族（POLICY_DENIED/UNKNOWN_TOOL/INVALID_ARGS/AUTH_FAILED/BUDGET_EXHAUSTED/STALE_GENERATION/RESULT_OVERSIZE——不触发模型重试循环） | WireMock timeout/cancel/oversize；**错误两族断言：可重试错误对模型可见且脱敏 / 权限·预算·代际错误不触发模型循环** |
| M4-18 | **V15** | 只读调用账本：状态沿用 **PENDING/SUCCESS/FAILED/UNKNOWN** + 原因码集（INVALID_INPUT/POLICY_DENIED/TIMEOUT/RATE_LIMITED/AUTH_FAILED/REMOTE_4XX/REMOTE_5XX/TRANSPORT_UNKNOWN/CANCELLED/REPLAY_MISS），唯一 operation_id；**不照搬 CrewAI 六分类；审批挂起态与 HMAC 审批 token 归 AM5，本任务不引入** | 断网/重复/UNKNOWN IT；原因码穷举 UT |
| M4-19 | **V16** | Evidence 契约与仓储：**EvidenceEnvelope 项目原生**（借鉴 in-toto envelope 思想，不照搬供应链 schema）；**四正交维度分别校验**——schema_version（结构版本）/observed_generation（事故代际）/snapshot_digest（冻结输入集）/payload_digest（内容完整性）；**digest 五步纪律**：入口 canonicalize 一次 → 存 canonical bytes → 对保存字节算 digest → 读出对原样字节重算比对 → schema 校验与 digest 校验分开 | 篡改检测、跨 generation 拒绝 IT；**同 schema_version 不同 generation 拒绝 IT**；**canonical bytes 原样回读+单字节篡改可检出 IT** |
| M4-20 | V16 同任务 | EvidenceSnapshot Builder：**snapshot_digest 显式列**（成员 digest 排序 canonical 后再哈希，不依赖 id 列）；冻结 = 新 snapshot 不可变+parent 链+CAS 提交；防篡改基线 = SHA-256 行摘要+快照聚合摘要+DB 权限隔离（**不引入 CRC/prev_digest 行链**——评审裁定无强防篡改收益） | 同事实同 digest；迟到证据不改旧快照；**相同证据但 generation/config/tool registry 变化 → digest 必变 UT** |
| M4-21 | **V17** | Claim 仓储（rca_claim 表）。【备料已落码 + GX-2 修正】**评审统一状态模型**：`claim_fingerprint`（身份=类型/键+scope+时间窗+generation+input snapshot）与 `claim_hash`（内容=状态+原因+证据引用+来源+策略版本）双标识；三正交字段 = 命题状态 TRUE/FALSE/UNKNOWN + 证据基础 SINGLE_SOURCE/MULTI_SOURCE_CONSISTENT/MULTI_SOURCE_CONFLICT + 生命周期 ACTIVE/SUPERSEDED；**不引入独立 verdict 枚举**；**不建空 Claim 表达无结论** | 缺引用/冲突 scope/版本错误测试 |
| M4-22 | — | Claim Reducer。【备料已落码 + GX-1 修正】**评审四分支**：fingerprint 不存在→`CLAIM_CREATED`；fp 同+hash 同→`CLAIM_UNCHANGED`；fp 同+hash 异→**CAS 更新当前投影**+追加 `CLAIM_REVISED`；新 generation/scope→新 fingerprint 不覆盖旧记录；无结论→幂等 `CLAIM_UNRESOLVED` 事件；判定事件连 none 也落库；**历史不可变**：证据失效→新观察+新快照+Claim 修订，历史报告不改、仅当前投影标 superseded（**弃级联撤销**） | 接线；**四分支矩阵 UT**；**历史快照/报告不被撤销反改 UT** |
| M4-23 | — | ReportAssembler（只从冻结 Snapshot + 已裁决 Claim 组装）：确定/推测分节形式可抄 HolmesGPT，**分节依据必须来自 M4-22 裁决状态机而非 LLM 自述** | 无证据不产根因；PARTIAL/UNRESOLVED UT |

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

## 阶段 D：新旧对照（M4-31~38，**AM3 G2 已过（2026-09-06）已解锁——按序衔接 M4-30 之后执行**）

M4-31 Holmes Adapter（依赖 M3-08）/ M4-32 REPLAY_MOCK / M4-33 Replay Runner / M4-34 Shadow Router / M4-35 Shadow 隔离 / M4-36 Source Reconciler / M4-37 Reconciler 预算统一件 / M4-38 AM4 G2——任务边界见拆解原文。（评审裁定备注：M4-37 三态分流——预算耗尽→终态、速率/临时→退避、**降级续跑必须白名单化**：仅只读工具+剩余预算内+记录降级原因+不得绕过权限/代际/证据约束；积压年龄维自实现挂窗口起点。）

---

## DoomLoopGuard（独立组件，评审裁定，随 M4-08 批次落地）

按 task+tool+action digest+**连续无进展结果**判定；命中仍扣一次 step；阈值配置化+版本化；reconciler 轮询等合法重复调用走不同策略；熔断产确定性事件+reason code；**熔断后零 LLM/tool 调用**。不并入裸 step counter。

## E2E-M4 业务端到端套件（评审增补，进 §阶段 C 验收）

E2E-M4-00 无故障不制造 RCA/候选通知；01 F1 同 generation 证据 + 命中 GT + 可回查；02 F2 证据缺失只 PARTIAL；03 F3 未对账必 UNKNOWN/PARTIAL；04 Claim 冲突 NEEDS_REVIEW 保留双方证据；05 generation 交替全 STALE 不污染；06 四杀点 SIGKILL 无重复执行/无预算透支/无永久 BLOCKED；07 prompt injection 被 Gateway 拒绝；08/09 待 M4-32~38。证据包必含：scenario_id/run/generation/DAG digest/snapshot digest/tool ledger/事件序列/Claim/Verdict/报告 digest/预算对账 + "Candidate 未发布"DB 断言。

## DoD（v1.3 修正版）

1. M4-01~30 单项验收全过（拆解原文验收列，含各任务行评审增补 IT/UT）
2. `mvn -q clean verify` 绿且 Failsafe IT 计数非零（**V12~V17** 迁移契约）
3. 分层铁律 ArchUnit 红绿留证；BA-22 关闭
4. **INV-AM4-8/9 红绿留证**：空策略/预算存储故障 fail-closed；耗尽与熔断路径零 LLM 调用；注册重名 fail-fast；未声明参数拒绝；伪造 annotation 被拒；外部源不可达只降 readiness
5. 195 真栈：DAG 固定链 + 崩溃恢复 + E2E-M4-00~07 证据（AA-26 契约）
6. 本期（M4-01~30）验收**不含** Replay/Shadow/Holmes 隔离（属 M4-32~38，已解锁，在阶段 D 验收）
7. 台账三件套同步

## 修订记录

| 版本 | 变更 |
|---|---|
| v1.0 | 初版（迁移编号 V10~V12，后被评审推翻） |
| v1.1 | 评审 7 P0 全采纳：迁移重排；M4-04 复用补强 + 同 run 连边约束；状态全集对齐冻结表；DoD 移除 Replay/Shadow/Holmes 隔离；Logs/Change 数据源限制；新增 GX-1~5 + E2E-M4 套件 |
| v1.2 | G1 通过登记 + 迁移顺延 V12~V17 + E-16 修正点落任务行（同日 G1 评审**退回**，见 v1.3） |
| v1.3 | **G1 评审退回修订（与技术方案 v1.3 逐行对齐）**：① P0-1 门禁状态修正（AM3 G2 用户已通过；AM4 G1 未签，编码回到预研/备料口径，撤销"正式开工"表述）；② P0-2 迁移 V12~V17 正式裁定；③ P0-3 消除双轨——修正点全部并入任务行正文语义；④ 语义收紧落行：M4-08 幂等业务键/取消双路径/不持锁过网络/报告专项预算/评审七条 IT；M4-10 弃 global seq+回滚同事务+重放幂等语义；M4-14 启动/运行检查分离+"签名校验"更名"Java 方法签名与 Schema 一致性校验"；M4-15 未声明字段拒绝（弃静默裁字段）；M4-16 空策略硬失败+执行二次鉴权；M4-17/18 错误两族+既有四态原因码（弃 CrewAI 六分类/审批态归 AM5）；M4-19 四正交维度+digest 五步纪律+EvidenceEnvelope；M4-20 弃 CRC/行链；M4-21/22 双哈希四分支+三正交字段（废 verdict 枚举）+CLAIM_UNRESOLVED+历史不可变；DoomLoopGuard 独立成节；阶段 D 降级续跑白名单化 |
