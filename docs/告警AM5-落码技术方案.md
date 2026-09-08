# 告警 AM5 评测发布门与长期运维 —— 落码技术方案（执行者用）v1.0

> 定位：AM5 编码的**执行施工图**。只细化、不翻案——凡与上游冲突，以上游为准并回本方案登记差异。
> 上游依据（按优先级）：
> 1. `docs/告警AM5-技术方案.md` **v1.2**（2026-09-07，**G1 已由用户签署**）——方案唯一权威；
> 2. `docs/告警Agent-增量实现任务拆解-v1.md` **§8**——M5-01~22 任务编号/依赖/验收的唯一权威，本方案不新增编号；
> 3. `docs/告警AM5-执行交接文档.md`——边界裁定：迁移从 **V20** 起、模型 qwen3.7-plus（thinking 型/费率 TODO/eval 基线重建）、**不碰 195**、工序 3 纪律（PROGRESS 只增不删 + BUGLOG 接 BA-34 之后）；
> 4. 格式模板：`docs/告警AM4-落码技术方案.md`（v1.3+附录 v1.4）。
> 环境锚点：本机无 Docker，`*IT`（Testcontainers 真 PG）自动跳过且**不计入完成证据**；本地只报 `mvn -B -ntp clean test` 的 UT 基线（交接时 634 绿）。每个任务开工前先核对 HEAD 与迁移目录实际最大号。
> API 契约来源：`docs/告警-前端页面设计-wireframes-v1.html` v1.6 的 P3/P4/P5 annot + AM7 前端 mock 实现（`alert-web/src/mocks/{cases,runs,eval}.js`、`alert-web/src/api/client.js`，baseURL=`/api`）。**前端已按这些契约实现，后端落码必须与字段形状一致**；annot 与拆解冲突处以拆解为准并登记开放项。

---

## 迁移编号段分配表（V20~V29，开工重冻结）

交接文档裁定：AM4 已实际占至 **V19**（`V19__am4_tool_replay.sql`），技术方案 v1.2 §2 原文"AM5 自 V19 起"已过时，按方案§2 自带条款"以当时实际最大编号+1 起排并在落码方案重冻结"重排如下。**一迁移一任务；已发布迁移不得追加（INV-AM5-10 顺延适用）**；下表号段在 M5-01 开工前用 `find */src/main/resources/db/migration -name 'V*.sql'` 实测复核一次，若 AM4 再顺延则整体后移。

| 迁移号 | 文件名【新增】 | 任务 | 内容 | 来源 |
|---|---|---|---|---|
| V20 | `V20__am5_dataset_version.sql` | M5-01 | `dataset_version` + `case_version`（insert-only） | 方案原 V19 顺延 |
| V21 | `V21__am5_dataset_partition.sql` | M5-02 | 四分区 schema/角色/RLS + GT 延迟授权（沿用 V11 模式） | 方案原 V20 顺延 |
| V22 | `V22__am5_golden_candidate.sql` | M5-03 | `golden_candidate` + `golden_review_event` | 方案原 V21 顺延 |
| V23 | `V23__am5_sampling_fingerprint.sql` | M5-04 | `rca_attempt` 采样指纹列（或侧表，开工定，见 M5-04②） | 【落码细化新增——方案六迁移清单未单列，G 门备查】 |
| V24 | `V24__am5_config_bundle.sql` | M5-09 | `config_bundle`（immutable）+ `config_bundle_active`（pointer） | 方案原 V22 顺延 |
| V25 | `V25__am5_canary_route.sql` | M5-10 | `rca_run` 增列（engine/config_digest/bucket）+ `canary_route_decision` + 活跃唯一索引调整 | 【落码细化新增，理由见 M5-10②】 |
| V26 | `V26__am5_operator_case.sql` | M5-11 | `operator_case` + notify_outbox 关联列 | 方案原 V23 顺延 |
| V27 | `V27__am5_operator_command.sql` | M5-14 | `operator_command`（命令先持久化面） | 【落码细化新增】 |
| V28 | `V28__am5_monthly_partition.sql` | M5-18 | `rca_event`/`rca_evidence`/`alert_inbox` 月 RANGE 分区改造 | 方案原 V24 顺延 |
| V29 | `V29__am5_retention_archive.sql` | M5-18/19 | `retention_policy` + `legal_hold` + `archive_manifest` | 【落码细化新增】 |

M5-20 FTS 默认关闭：**本方案不预占迁移号**；若实验开门再实测最大号+1 单独排（索引/列随实验评审冻结）。M5-21 pgvector 同理（不过门不启用，INV-AM5-9）。

---

## 阶段 A：数据与权限底座（M5-01~03）

### M5-01 DatasetVersion/CaseVersion + 统一 DatasetAdapter

**① 落点清单**

- 【新增】迁移 `V20__am5_dataset_version.sql`。
- 【新增】`control-app/src/main/java/com/objwww/pr/control/eval/domain/model/DatasetVersion.java`、`CaseVersion.java`（record，insert-only 版本实体；domain 零框架）。
- 【新增】`eval/domain/model/EvalCaseV1.java`——外部数据统一内部契约（Adapter 输出类型）。
- 【新增】`eval/domain/port/DatasetAdapter.java` + 三实现 `eval/infrastructure/adapter/OrderArenaAdapter.java`、`Rca100Adapter.java`、`RcaEvalAdapter.java`（infra 层；Adapter 不触评分）。
- 【新增】`eval/domain/repository/DatasetVersionRepository.java`（接口在 domain，实现在 infrastructure，沿用 `EvalRunRepository` 既有分层惯例）。
- 引用既有：`GoldenScenarioRegistry`（5 场景注册表，订单域私有集的 scenario 来源）、`eval-scenarios.yml`。

**② 迁移 DDL 要点（V20）**

风格照 V10/V14：头部注释块 + snake_case + 约束命名 `pk_/fk_/uq_/ck_/ix_` + `comment on table` + 末尾 grant/revoke。

- `dataset_version`：`id uuid pk`、`source`、`name`、`version`、`source_uri`、`license`、`access_class`、`content_digest char(64)`、`adapter_version`、`imported_at timestamptz`——**来源九字段全集**（方案 §3.1，v1.1）；另加 `source_class varchar(24) check in ('PRIVATE','PUBLIC_BENCHMARK')`、`partition_class varchar(16) check in ('TUNING','VALIDATION','HOLDOUT','REDTEAM')`、`scenario_family_id`（整组分区键列在 case 侧，dataset 侧存家族清单 digest）。`unique (name, version)`；**只授 select,insert，无 update/delete**（insert-only 的 DB 面）。
- `case_version`：`id uuid pk`、`dataset_version_id fk`、`case_key`、`scenario_family_id not null`、`valid_from`/`valid_to`（适用期）、`content_digest char(64)`、`payload jsonb`（EvalCaseV1 canonical）、`source_artifact_ref text`（原始 artifact 引用，不覆盖来源字段）。`unique (dataset_version_id, case_key)`；同 insert-only 授权。
- 授权：`eval_app` select,insert；`control_app`/`notify_app`/arena 角色显式 revoke（照 V9 §5 的条件化 `do $$ ... if exists ...` 幂等块）。

**③ 接口契约**：本任务无 HTTP API。Adapter 端口签名（供 M5-05/06 复用）：`List<EvalCaseV1> importCases(ImportRequest req)`，`req` 含 `datasetRef{name,version,manifestDigest}`——**RCA-100 固定 v1.1+manifest digest，禁 latest**；answer key 未获授权时返回/落 `NOT_AVAILABLE_AUTH` 态（fail-closed，严禁伪造答案或以 RCAEval 顶名——方案 §12.3 E2E-AM5-02 原文）。

**④ 验收命令与通过标准**

- `mvn -B -ntp -pl control-app test -Dtest='DatasetAdapterTest,Rca100AdapterTest,RcaEvalAdapterTest,OrderArenaAdapterTest'`【新增测试类】：EvalCaseV1 转换、原始 artifact 保留、版本九字段、manifest digest 锚定、**family 整组分区断言**（同 `scenario_family_id` 拆分跨 TUNING/HOLDOUT 直接拒绝）。
- 历史不可覆盖、适用期校验 UT（拆解 §8 M5-01 验收列）。
- IT（V20 迁移契约、insert-only 双写拒绝）随 L2 套件，本机跳过、不计证据。

**⑤ 依赖与并行**：依赖 M4-38（拆解原文；已被用户豁免与 M4 E2E 并行——见交接文档 §一）。**M5-01 是一切的地基，必须最先做，不与任何任务并行**。M5-02/03 在其后可互相并行。

### M5-02 数据集四分区权限

**① 落点清单**

- 【新增】迁移 `V21__am5_dataset_partition.sql`。
- 【新增】`eval/infrastructure/identity/EvalIdentityGuard.java`（查询侧身份校验：Agent/RAG 身份对 HOLDOUT/GT 查询恒 0）。具体类名开工时可与 V11 既有只读授权实现核对后定名。
- 引用既有：`V11__am3_eval_readonly.sql`（GT 延迟授权模式——沿用，不另起炉灶）。

**② 迁移 DDL 要点（V21）**

- 四分区物理隔离：每分区独立 schema 或 `case_version` 行级 `partition_class` + RLS 策略二选一——**落码建议：RLS + 分区角色**（`eval_tuning_ro`/`eval_validation_ro`/`eval_holdout_gate`/`eval_redteam_gate`），HOLDOUT 表面对 Agent/RAG/调优界面身份 `enable row level security` + 零可见策略；GT 列沿用 V11 延迟授权（评分身份专属 grant，报告封存后才可读）。
- `PUBLIC_BENCHMARK` 身份行不得出现在私有 HOLDOUT 面（INV-AM5-1：公共 benchmark 不得冒充私有 HOLDOUT）。
- DDL 约束：`ck_case_version_partition` 枚举四值；家族整组分区由 M5-01 应用层断言 + 本迁移 `case_version` 上 `unique (scenario_family_id, partition_class)` 兜底（同 family 跨分区 = 违约）。

**③ 接口契约**：无 HTTP。权限矩阵即契约：评测查询 API（后续 M5-08/12 落码时）一律以受限服务端身份访问 eval 表——annot 原文"浏览器不得直接访问评测数据库"（P5 annot「权限」行）。

**④ 验收**：`mvn -B -ntp -pl control-app test -Dtest='DatasetPartitionAccessTest'`【新增】身份矩阵 UT；IT 面 = Agent/RAG 身份对 HOLDOUT/GT 查询为 0、PUBLIC_BENCHMARK 身份对私有 HOLDOUT 查询为 0、同 family 拆跨分区拒绝（方案 §12.1 L2 原文）。本机跳过。

**⑤ 依赖与并行**：依赖 M5-01；与 M5-03 **可并行**（不同迁移号、不同文件面）。

### M5-03 Golden Candidate 工作流

**① 落点清单**

- 【新增】迁移 `V22__am5_golden_candidate.sql`。
- 【新增】`eval/domain/model/GoldenCandidate.java` + `eval/domain/statemachine/GoldenCandidateStateMachine.java`（DRAFT→REVIEW→PUBLISHED/REJECTED/WITHDRAWN；穷举转换表，照 AM4 `alert/domain/statemachine` 惯例）。
- 【新增】`eval/application/GoldenCandidateService.java`（双人复核事务：两签不同人同事务校验）。
- 【新增】`eval/domain/repository/GoldenCandidateRepository.java`。

**② 迁移 DDL 要点（V22）**

- `golden_candidate`：`id uuid pk`、`case_version_id fk`、`proposed_gt jsonb`、`reason text`、`state varchar(16) check in ('DRAFT','REVIEW','PUBLISHED','REJECTED','WITHDRAWN')`、`proposed_by`、`reviewer_a`/`reviewer_b`、`ck_golden_dual_review check (reviewer_a is null or reviewer_b is null or reviewer_a <> reviewer_b)`（**同人不能双签的 DB 兜底**——INV-AM5-2）、revision/created/updated。
- `golden_review_event`：append-only 复核事件（candidate_id fk、action、actor、expected_revision、idempotency_key unique、payload jsonb）——只授 select,insert。
- 授权：eval_app 读写候选；发布动作只更新 `case_version` 新行（insert-only 不破）。

**③ 接口契约**：无 HTTP（先 API 后 UI 的 UI 在 AM7；P5-C 人工复核面只消费 review_event 查询，查询 API 属开放项 O-2）。

**④ 验收**：`mvn -B -ntp -pl control-app test -Dtest='GoldenCandidateStateMachineTest,GoldenCandidateServiceTest'`【新增】：状态机穷举 + 同人双签拒绝 UT；IT = 发布/拒绝/撤回无旁路、双签同事务。拆解 §8 验收列："同人不能双签、拒绝/撤回/发布状态机"。

**⑤ 依赖与并行**：依赖 M5-01；与 M5-02 并行。

---

## 阶段 B：评测与门禁（M5-04~08）

### M5-04 模型采样指纹

**① 落点清单**

- 【新增】迁移 `V23__am5_sampling_fingerprint.sql`。
- 【新增】`eval/domain/model/SamplingFingerprint.java`（value object：temperature/top_p/max_tokens/seed 请求+生效两态/provider fingerprint/trial 序号；缺字段不可进正式门禁——FUT-40/方案 §3.1）。
- 【修改】`alert/application/agent/` 下 LLM 调用点（AM4 Native Agent 的 ChatClient 封装处，开工时以 `grep -rn 'ChatClient\|call(' alert/application/agent` 定位）：把每 Attempt 的生效采样参数回写落库。
- 引用既有：`eval_run`（V10）已有 run 头粒度 temperature/top_p/max_tokens/requested_seed/effective_seed/provider_fingerprint——**M5-04 把它下沉到 attempt 粒度并加两态分离**；qwen3.7-plus 是 thinking 型（reasoning_tokens 计入 completion_tokens、max_tokens 过小 content 为空——交接文档 §四），指纹必须记录 max_tokens 生效值。

**② 迁移 DDL 要点（V23）**——开工两选一并与技术方案 §6「采样指纹」条目核对后定：

- 方案甲（首选）：`alter table rca_attempt add column sampling_fingerprint jsonb` + `ck` 校验必填键集（请求态/生效态分列于 jsonb 内）；或
- 方案乙：侧表 `attempt_sampling_fingerprint`（attempt_id fk unique、requested_seed/effective_seed、provider_fingerprint、trial_no、model、temperature、top_p、max_tokens、config_digest）。
- 无论甲乙：**正式门禁消费面只认指纹完整行**（缺字段 = 该 Attempt 不进门禁统计，INV-AM5-3）。

**③ 接口契约**：无 HTTP。领域不变量：`SamplingFingerprint.isGateEligible()` false 时 EvalGateRunner 拒绝纳入正式门禁。

**④ 验收**：`mvn -B -ntp -pl control-app test -Dtest='SamplingFingerprintTest'`【新增】：固定样例指纹 digest 稳定、缺字段拒绝 UT（**凡涉及指纹/摘要的断言必须实测校准**——方案 §10 BA-22 教训原文）。拆解验收："缺字段不可进入正式门禁"。

**⑤ 依赖**：M4-33（拆解原文）；与 M5-01~03 **可并行**（不同包面），但 M5-05 必须在它之后。

### M5-05 配对重复试验与方差

**① 落点清单**

- 【新增】`eval/domain/service/PairedTrialStats.java`——纯函数，不触网（方案 §3.1）。
- 【修改】`eval/application/EvalBatchRunner.java`：跑批配方内建**批间隔离三件套**（等 resolve 投递/清 nflog/临时放宽 group_interval，事后还原——方案 §10 AM3 实测教训），支持 rounds>1 配对重复。
- 【新增】`eval/domain/model/PairedTrialReport.java`（重复次数/翻转率/配对差值区间/stats_seed/算法版本/重采样次数/CI 方法全记录）。

**② 迁移**：无新迁移（报告落 `eval_run` 既有元数据或随 V23 指纹面引用；若需新列走 V23 同迁移，不另占号）。

**③ 接口契约**：纯函数签名（冻结统计口径，方案 §3.1 v1.1 原文援引）："配对差值按 `independence_group_id`/`scenario_family_id` 聚类 + bootstrap 整组有放回重采样（默认 1000 次）；**cluster bootstrap 不再套 C/(C-1) 修正——C/(C-1) 是 clustered SE 的有限 cluster 修正，两算法不混写**（E-17：Inspect AI `std.py:18-51,109-115`）；**独立 cluster 数不足返回 INCONCLUSIVE，禁止收窄到零的假区间**"。返回类型 `StatsResult{pointEstimate, ciLower, ciUpper, clusterCount, statsSeed, algorithmVersion, resamples, ciMethod, verdict: CONCLUSIVE|INCONCLUSIVE}`。

**④ 验收**：`mvn -B -ntp -pl control-app test -Dtest='PairedTrialStatsTest'`【新增】：固定样例翻转率/区间/整组重采样不套修正/stats_seed 全记录/cluster 不足 INCONCLUSIVE 禁假区间（方案 §12.1 L1 逐条）。拆解验收："固定样例统计测试"。

**⑤ 依赖**：M5-04；与 M5-02/03 可并行收尾。

### M5-06 六维 Evaluator

**① 落点清单**

- 【新增】`eval/domain/service/SixDimEvaluator.java` + 六维原始计数模型 `eval/domain/model/DimensionCounts.java`（结果/过程/工具/成本/协作/安全）。
- 【修改】`eval/application/ScenarioEvaluator.java`、`SingleCaseScorer.java`：AM3 三维精确匹配**演进**为六维（技术债条款："演进非推翻"，方案 §11）；`SynonymLexicon` 继续作为 canonical 命中的版本化白名单。
- 引用既有：`alert/domain/claim/Claim.java`（过程/协作维消费 Claim 裁决状态）、`alert/domain/event/RcaEventAppender.java` 读面（每项原始计数可追溯到 event/evidence——拆解验收列）、`alert/domain/budget/RunBudget.java`（成本维）、`UsageLedgerService`（LiteLLM 对账——**注意费率字段是 deepseek-v3 旧值占位，成本维对账口径标 TODO，刊例价校准归运维项**，交接文档 §四）。

**② 迁移**：无（计数列若需落表随 M5-08 门禁记录面，见 M5-08②）。

**③ 接口契约**：`SixDimEvaluator.evaluate(EvalCaseInput) -> SixDimResult`，每维 `{rawCounts, traceRefs[], score?}`——**不聚合成单一分**（方案 §3.1）。**AC@1 限定（v1.1 评审裁定，方案 §3.1 原文）**："结果维只用 AC@1——无 `candidate_root_causes[]` 契约时禁算 AC@3/5；chance/lift 只校正随机排名基线、不解小样本"。当前单根因输出 = 只算 AC@1；调 AC@3/5 的代码路径直接抛拒绝异常。

**④ 验收**：`mvn -B -ntp -pl control-app test -Dtest='SixDimEvaluatorTest'`【新增】：六维各自原始计数纯函数 UT + AC@3/5 无契约时拒绝 UT。拆解验收："每项原始计数可追溯到 event/evidence"。

**⑤ 依赖**：M5-02、M5-05（拆解原文）。

### M5-07 硬安全门

**① 落点清单**

- 【新增】`eval/domain/service/SafetyGate.java`——schema/越权/注入/跨租户/写意图五面检查，**任一失败 fail-closed**（INV-AM5-4）。
- 引用既有：`alert/domain/tool/` 的 ToolPolicy/ToolGateway 错误两族（AM4 已落码的权限/写意图拦截面，安全门消费其拒绝记录而非重判）。

**② 迁移**：无（违规计数进 M5-08 门禁记录）。

**③ 接口契约**：`SafetyGate.check(SixDimResult, EvalCaseInput) -> SafetyVerdict{violations[], verdict: PASS|REJECT}`；violations 元素带五面分类码（机器码英文，P5 annot 中文口径契约）。

**④ 验收**：`mvn -B -ntp -pl control-app test -Dtest='SafetyGateTest'`【新增】：五面正反样本 UT；L4 红队输入穿过真实门禁 E2E 资产（E2E-AM5-03）随本任务同步交付脚本骨架（`docs/测试证据/AM5/e2e-脚本/e2e-am5-03-safety-gate.sh`【新增】，部署段执行）。

**⑤ 依赖**：M5-06。与 M5-08 串行（门禁记录面在 M5-08）。

### M5-08 质量门与运行门

**① 落点清单**

- 【新增】`eval/domain/service/QualityGate.java`、阈值模型 `eval/domain/model/GateThresholds.java`（版本化，阈值随 ConfigBundle 走）。
- 【新增】`eval/application/EvalGateRunner.java`——编排：复用 `EvalBatchRunner` 跑批 → SixDimEvaluator → SafetyGate/QualityGate → **EvaluationRecordV1 落档**（真实性标签 LIVE_BUSINESS/HYBRID_BUSINESS/PUBLIC_REPLAY/CONTROL_FIXTURE 必须写入——方案 §12.2 原文）。
- 【新增】`eval/domain/repository/EvaluationRecordRepository.java`。

**② 迁移 DDL**：**门禁记录落 `eval_run` 扩展列还是独立表，开工时与 V10 schema 核对后定**——若独立表（建议：`evaluation_record`：eval_run_id fk、gate_verdict 五分支枚举、每维计数 jsonb、ci jsonb、gate_reasons jsonb、真实性标签、stats_seed/算法版本、dataset/config/engine digest 集）则**并入 V24 之前的空档需用户确认**；本方案默认：不加新迁移号，扩展列挂 V23 同任务窗口内的复审裁定。**该点列入开放项 O-1。**

**③ 接口契约**：**质量门五分支冻结逻辑（v1.1 评审裁定，方案 §3.1/§6 原文援引，逐字执行）**：
1. 安全违规>0 → REJECT；
2. 独立 cluster/关键分层数量不足 → INCONCLUSIVE；
3. 任一关键维配对差值 CI 下界 < -margin → REJECT；
4. 运行门超预算/延迟/错误率 → REJECT；
5. 全部通过 → ELIGIBLE_FOR_CANARY。

门禁输出必须含完整解释（每维计数+区间+未过原因）；**小样本不自动放行**（拆解 §8 验收列）；在线 Canary 无 GT 只判安全/运行/成本/disagreement（INV-AM5-6），不在本门禁消费面。

**④ 验收**：`mvn -B -ntp -pl control-app test -Dtest='QualityGateTest,EvalGateRunnerTest'`【新增】：五分支全路径 UT（安全违规→REJECT/cluster 不足→INCONCLUSIVE/CI 下界→REJECT/运行门超→REJECT/全过→ELIGIBLE）、阈值版本化、解释完整性（方案 §12.1 L1 原文清单）。

**⑤ 依赖**：M5-06（M5-07 合流）。**阶段 B 收口任务，阶段 C 的唯一入口。**

---

## 阶段 C：发布与切流（M5-09~10）

### M5-09 ConfigBundle 发布/回滚

**① 落点清单**

- 【新增】迁移 `V24__am5_config_bundle.sql`。
- 【新增】`release/domain/model/ConfigBundle.java`（不可变：prompt/规则/工具策略/模型路由/阈值 + bundle digest/revision + **策略版本字段——本期不引入 OPA/WAITING_APPROVAL**，v1.1 评审裁定，方案 §3.3 原文："WAITING_APPROVAL 与 OPA PDP 本期整体不引入；本期 ConfigBundle 仅保留策略版本字段，R2/R3 真正启用时再评估 OPA"）、`release/domain/repository/ConfigBundleRepository.java`。
- 【新增】`release/application/ConfigBundleService.java`：Git 编辑源 → PG immutable bundle 落库 → **单事务原子激活（无半激活态）** → active pointer CAS；回滚 = pointer 指回旧 digest，不改历史行。
- 【新增】`release/interfaces/ConfigBundleController.java`（发布/回滚/查询 API，RBAC=release 角色；先 API 后 UI）。
- 新顶层包 `com.objwww.pr.control.release/`（方案 §3.1 已划 `release/domain/`）；ArchUnit 分层规则套件需补 release 包白名单——【修改】既有 ArchUnit 测试（开工 `grep -rln 'ArchUnit\|com.tngtech' control-app/src/test` 定位）。
- 存量配置清点迁移（散在 yml/env/compose 的键 → bundle 首版）：方案 §11 技术债条款要求 M5-09 设计含清点——落码交付物 = `deploy/` 下配置清点清单文档片段入 PROGRESS，不新建独立文档。

**② 迁移 DDL 要点（V24）**

- `config_bundle`：`id uuid pk`、`bundle_digest char(64) unique`、`revision bigint not null`、`content jsonb not null`（prompt/规则/工具策略/模型路由/阈值/policy_version）、`created_by`、`created_at`；**只授 select,insert（immutable 的 DB 面）；密钥不入 bundle（INV-AM5-5）**。
- `config_bundle_active`：单行 pointer（`id smallint pk check (id=1)`、`bundle_digest fk`、`activated_at`、`activated_by`）——激活/回滚 = 该行 CAS update（WHERE bundle_digest=旧值），无半激活态；历史 bundle 行永不改。

**③ 接口契约**

- `POST /api/config-bundles`：body `{content{...}, expectedRevision?}`，幂等键 = 客户端 `idempotency_key` 头；响应 `{bundleDigest, revision}`。
- `POST /api/config-bundles/{digest}/activate`：body `{canaryPercent, whitelist[], idempotencyKey}`；单事务原子激活。
- `POST /api/config-bundles/rollback`：body `{toDigest, idempotencyKey}`；pointer CAS 指回。
- `GET /api/config-bundles/active`：响应 `{bundleDigest, revision, activatedAt}`。
- 全部写端点 RBAC=release + 审计事件（决策可回溯：每次裁决记录 bundle digest/revision——方案 §8 设计原因"ConfigBundle 抄 OPA 四件套"）。

**④ 验收**：`mvn -B -ntp -pl control-app test -Dtest='ConfigBundleTest,ConfigBundleServiceTest'`【新增】UT；IT（本机跳过）= bundle 原子激活/回滚不改历史/老 Run 固定旧 digest。拆解验收："老 Run 固定旧 digest；回滚不改历史行"。E2E-AM5-04（CONTROL_FIXTURE 原子激活→运行中回滚）脚本随件交付。

**⑤ 依赖**：M5-08。阶段 C 串行链起点。

### M5-10 Canary Router 稳定分桶

**① 落点清单**

- 【新增】迁移 `V25__am5_canary_route.sql`。
- 【新增】`release/domain/service/CanaryBucketer.java`——纯函数：murmur3(`groupId:id`) 归一化 + flagd 无模偏公式 `(hash*totalWeight)>>32`（E-17：`fractional.go:196-207`）；**无 stickiness key = 拒绝放量**（修 Unleash random 回退坑，方案 §8）。
- 【新增】`release/application/CanaryRouter.java`：新 Run 创建点的路由决策（读 active bundle + 分桶 + 爆炸半径上限；超上限自动停放量并告警；**立即回退 Holmes 为一等操作**）。
- 【修改】`alert/application/RcaRunOrchestrator.java` 新 Run 铸造点接 CanaryRouter（ Holmes 主路径默认桶，Native 候选桶）；`alert/application/Am4ShadowTrigger.java`（AM4 Shadow 触发点——Canary 与 Shadow 的关系裁定见开放项 O-3）。

**② 迁移 DDL 要点（V25）——本方案新增号段，理由如下（矛盾点 C-2）**：

- 现状 `rca_run`（V7）**无 engine/config_digest 列**，且 `uq_rca_run_active_incident` 唯一索引（incident_id where QUEUED/RUNNING）只允许同 incident 一个活跃 Run——Canary/Shadow 双路并存会违约。
- V25 内容：`alter table rca_run add column engine varchar(16) not null default 'HOLMES' check in ('HOLMES','NATIVE')`、`add column config_digest char(64)`（Run 启动固定，不再变——方案 §4.1"路由决策+config_digest 落 Run"）、`add column stickiness_key text`、`add column canary_bucket integer`；唯一活跃索引调整为 `(incident_id, engine)` 粒度（**该索引改动影响 AM4 Shadow 既有行为，开工时必须先与 `Am4ShadowTrigger.java` 现行铸造路径核对**，见其 line 109 以 RunTrigger.RERUN 铸造）；新表 `canary_route_decision`（append-only：run_id fk、stickiness_key、bucket、percent、bundle_digest、decision、created_at——路由决策/比例/bucket/digest 全审计，E2E-AM5-05 断言面）。

**③ 接口契约**：无独立 HTTP（路由发生在 Run 铸造内部）；查询面经 M5-13 `GET /api/rca-runs` 投影暴露 engine/config digest（P3 annot crumb 行已有 `engine native ｜ config 9c1e…` 展示契约）。**E2E-AM5-05 边界：不得接生产告警流量，生产 1% Canary 属 M6-01**（方案 §12.3 原文）。

**④ 验收**：`mvn -B -ntp -pl control-app test -Dtest='CanaryBucketerTest,CanaryRouterTest'`【新增】：分布均匀性/黏性（同 key 恒桶）/无 key 拒绝/无模偏/爆炸半径超限自动停 UT；IT（本机跳过）= 老 Run 固定 digest、回退只影响新 Run。拆解验收："比例、黏性、立即回 Holmes 演练"。E2E-AM5-05 脚本随件交付。

**⑤ 依赖**：M5-09。阶段 C 收口；D 阶段 M5-11/12 在其后。

---

## 阶段 D：人工与观测收口（M5-11~17）

### M5-11 OperatorCase 状态机

**① 落点清单**

- 【新增】迁移 `V26__am5_operator_case.sql`。
- 【新增】顶层包 `com.objwww.pr.control.ops/`（方案 §3.1 已划 `ops/domain/`）：`ops/domain/model/OperatorCase.java` + `ops/domain/statemachine/OperatorCaseStateMachine.java`（**ISA 18.2 式 action 驱动状态机**，转换规则名落日志可审计——E-17：alerta `isa_18_2.py:99-140`）+ `ops/domain/repository/OperatorCaseRepository.java`。
- 【新增】`ops/application/OperatorCaseService.java`：幂等合并（`(tenant,fingerprint)` FOR UPDATE——E-17：keep `db.py:5690-5706`）+ **并发认领 CAS（expected-version）** + SLA 升级事件。
- 引用既有：`alert/domain/event/RcaEventAppender.java`（Case 事件落 rca_event 或独立审计——开工裁定，默认独立表内嵌 activities/audits jsonb 投影由 V26 表列支撑，见 P4 mock `audits[]` 形状）；`notify_outbox`（V9，SLA 升级/分配通知复用其 IN_APP 渠道面——**notify-app 新增 IN_APP 渠道属 AM7/通知侧配套，本任务只落关联列**）；`alert/domain/budget/` 预算耗尽信号（M4-37 Reconciler 输出为 Case 来源之一）。

**② 迁移 DDL 要点（V26）**

- `operator_case`：`id uuid pk`、`tenant`、`fingerprint varchar(64)`、`unique (tenant, fingerprint)`（幂等合并键）、`subject`、`priority`、`reason_code`（机器码英文：CLAIM_CONFLICT/BUDGET_NEAR_LIMIT/REPORT_REVIEW/ACTION_FAILED/NOTIFY_RETRY_EXHAUSTED 等，对齐 `alert-web/src/mocks/cases.js` 用例集）、`status varchar(16)`（OPEN/ACKED/RESOLVED/…——状态全集以状态机穷举表为准，开工与前端 `status` 值核对）、`owner`、`run_id`/`task_id` 来源引用、`snapshot_digest`/`observed_generation`（Case 固定创建时快照——P4 annot"迟到证据进入新快照，不改写旧裁决上下文"）、`evidence_refs jsonb`（**1..N 可核验来源引用，N≥1**）、`first_seen/ack_due/resolve_due`、`revision bigint not null default 1`（CAS 列）、created/updated。
- `notify_outbox` 增关联列（`case_id uuid null`）——沿用"已发布迁移不得追加"原则在 V26 内 alter。

**③ 接口契约**：无 HTTP（API 在 M5-12）。合并语义：同 `(tenant,fingerprint)` 再发生 = 既有单 `revision+1` + activity 追加，不新建单；结案不回写篡改 Run 结论（方案 §3.1"不做"列）。

**④ 验收**：`mvn -B -ntp -pl control-app test -Dtest='OperatorCaseStateMachineTest,OperatorCaseServiceTest'`【新增】：非法迁移拒绝穷举、合并幂等 UT；IT（本机跳过）= 幂等合并 + 并发认领 CAS 恰一人成功 + 升级 outbox 恰一次。拆解验收："连续三次、一小时聚集、SLA 升级测试"。E2E-AM5-06 脚本随件交付。

**⑤ 依赖**：M4-37（拆解原文）；与 M5-09/10 **可并行**（方案 §2 阶段 D 依赖行允许），但 M5-12 必须在它之后。

### M5-12 Operator API 最小集

**① 落点清单**

- 【新增】`ops/interfaces/OperatorApiController.java`；RBAC/审计切面沿用项目既有安全件（**现状 control-app 无用户体系与查询 API——P5 annot 缺口行原文"全系统当前 0 个面向人的查询 API、0 用户体系"，RBAC 落地形态开工与架构 §17 安全章节核对**，列开放项 O-4）。
- 【新增】`ops/application/OperatorQueryService.java`（只读投影）。

**② 迁移**：无。

**③ 接口契约（以 P4 annot + `alert-web/src/mocks/cases.js` + `CasesView.vue` 实际调用为准，字段级）**

- `GET /api/cases/summary` → `{buckets{mine,all,unassigned,overdue}, notifications, updatedAt}`（前端 tab 计数）。
- `GET /api/cases?bucket=&status=&priority=&reasonCode=&cursor=` → `{cases:[{id,subject,priority,status,owner,reasonCode,incidentType,runId,taskId,firstSeen,ackDue,resolveDue,evidenceCount,revision}],nextCursor}`——游标分页+稳定排序；默认按 SLA 风险排序。
- `GET /api/cases/{id}` → `{headline,summary,sourceRefs{count,text},snapshot,generation,evidence[{id,type,source,window,generation,verify,digest,summary,taskId}],claims[{id,text,verdict,evidenceRefs[]}],activities[],audits[{time,actor,action,revision,key}]}`——证据只回安全摘要与 digest，**不回 canonical payload**（P4 annot）。
- 命令端点（四类，全部 `expected_revision + idempotency_key`，旧 revision 返回 **409 冲突并携带最新投影刷新**——P4 annot 原文）：
  - `POST /api/cases/{id}/claim` body `{expectedRevision, idempotencyKey}`
  - `POST /api/cases/{id}/ack` 同上
  - `POST /api/cases/{id}/resolve` body `{expectedRevision, idempotencyKey, reason{code,note}}`（**解决必须填结构化 reason 与备注**）
  - `POST /api/cases/{id}/assign` body `{expectedRevision, idempotencyKey, toOwner}`（转派）
- RBAC + 审计（拆解验收列）；机器码英文、中文名前端词典渲染（P3 annot 中文口径契约，M7-09 词典不动）。

**④ 验收**：`mvn -B -ntp -pl control-app test -Dtest='OperatorApiControllerTest'`【新增】（MockMvc）：RBAC 正反、并发认领 409、审计断言。拆解验收："RBAC、并发认领、审计测试"。**联调验收 = 前端 `VITE_USE_MOCK=false` 经 vite proxy `/api → control-app:8080` 四个页面行为不变**（`alert-web/src/api/client.js` 注释契约定义）。

**⑤ 依赖**：M5-11。与 M5-13 可并行开发（不同 controller），联调合流。

### M5-13 rca_event 查询/SSE

**① 落点清单**

- 【新增】`alert/interfaces/EventQueryController.java`、`alert/application/EventQueryService.java`、`alert/application/SseStreamService.java`。
- 引用既有：`rca_event`（V14：UNIQUE(run_id,seq)/append-only/`rca_run.last_event_seq` 同短事务——**表+游标是真相源，本任务零迁移**）；脱敏沿用 AM3 白名单渲染防线（`NotificationRenderer` 白名单/截断/[REDACTED] 遮蔽——**该类在 notify-app：`notify-app/src/main/java/com/objwww/pr/notify/domain/service/NotificationRenderer.java`**；control-app 侧 SSE 白名单过滤件需【新增】，规则对齐其口径，开工时先读该类核对白名单字段集）；**thought/原始 prompt/secret/完整工具参数永不进前端**（§17.9.3 红线）。

**② 迁移**：无。

**③ 接口契约（P3 annot + `alert-web/src/mocks/runs.js` 实际调用为准，字段级）**

- `GET /api/rca-runs?bucket=&severity=&owner=&cursor=` → `{summary{buckets{mine,running,stuck,failed,review},sla{overSla,oldestReadyWait,projectionLag},updatedAt},rows:[{id,incident,severity,bucket,stage,progress,blocker,owner,duration,action}],nextCursor}`——annot 原文："现有 RcaRunRepository 无此列表能力，正式方案必须列为新增后端配套"。
- `GET /api/rca-runs/{runId}` → `{run{id,incident,status,severity,engine,config,progress{done,running,blocked,total},budget{step,token,tool}},tasks[],edges[]}`（DAG 投影 = rca_task + rca_task_edge 既有表只读；payload 只含白名单摘要/引用/digest）。
- `GET /api/rca-runs/{runId}/events?after_seq=&limit=` → `{events:[{seq,eventType,taskId?,summary,payload(白名单),createdAt}],latestSeq,gap:false}`——初次读取用 `?after_seq=`。
- `GET /api/rca-runs/{runId}/events/stream`（SSE）：**每条消息写 `id: seq`**，浏览器重连以 `Last-Event-ID` 续传，服务端统一转换为同一 after_seq 游标；**实时鉴权 = stream ticket（TTL 30s 单次、绑 run+主体），禁止 URL 带长效 token**（annot 原文）；检测到 seq 缺口或超窗 → 停止增量并推 `resync` 事件，客户端全量重同步（Unleash delta API 先例）。
- 语义红线：**PG NOTIFY 只作唤醒信号，真身走表**（官方三边界 <8000B/8GB 队列/断连丢失——方案 §3.2/§8）；断线/慢客户端不取消不拖慢 Run（INV-AM5-8）；背压 = 慢客户端断开。

**④ 验收**：`mvn -B -ntp -pl control-app test -Dtest='EventQueryServiceTest,SseStreamServiceTest'`【新增】UT；L3 断线重连/慢客户端/游标过期/权限测试面（方案 §12.1）。拆解验收："断线重连、慢客户端、权限、游标过期测试"。E2E-AM5-07 SSE 半脚本随件交付。

**⑤ 依赖**：M4-10、M4-11（拆解原文，均已收口）。与 M5-12 并行。

### M5-14 Cancel/Hint/Feedback 命令

**① 落点清单**

- 【新增】迁移 `V27__am5_operator_command.sql`。
- 【新增】`alert/application/CommandService.java`——**先持久化再生效**；幂等命令 + 旧 revision 拒绝；Hint 标 UNTRUSTED 进上下文（方案 §3.2）。
- 【新增】`alert/interfaces/RunCommandController.java`。

**② 迁移 DDL 要点（V27）**

- `operator_command`：`id uuid pk`、`run_id fk`、`command_type varchar(16) check in ('CANCEL','HINT','FEEDBACK')`、`idempotency_key`、`unique (run_id, command_type, idempotency_key)`、`expected_revision bigint`、`payload jsonb`（Hint 文本等）、`state varchar(16) check in ('PERSISTED','APPLIED','REJECTED_STALE','REJECTED_FORBIDDEN')`、`actor`、`created_at/applied_at`；只授 select,insert + 终态列 update（V9 列级授权同构）。

**③ 接口契约（P3-B 头部按钮组 + `RunDetailView.vue:318-320` 前端契约为准）**

- `POST /api/rca-runs/{runId}/commands` body `{type:'CANCEL'|'HINT'|'FEEDBACK', idempotencyKey, expectedRevision, payload{...}}`；响应 `{commandId, state}`。
- 语义：先落 `operator_command`（PERSISTED）再生效；幂等键重放返回原 command；旧 expectedRevision → REJECTED_STALE + 409；越权 → REJECTED_FORBIDDEN + 403；**Hint 永远以 UNTRUSTED 身份进上下文**；断线语义 FUT-33：断线不得改变 Run 状态。
- **annot 明列的边界（必须遵守）**：P5 annot"发起批量评测"命令 API **不在 M5-14 的 Cancel/Hint/Feedback 范围内**，"正式方案须先在任务拆解增补编号"——本方案不实现该命令，列开放项 O-2，执行者不得擅自扩展。

**④ 验收**：`mvn -B -ntp -pl control-app test -Dtest='CommandServiceTest,RunCommandControllerTest'`【新增】：幂等/旧 revision/越权/先持久化（生效前崩溃重放）UT。拆解验收："幂等命令、旧 revision、越权测试"。E2E-AM5-07 命令半脚本随件交付。

**⑤ 依赖**：M5-12、M5-13（拆解原文）。

### M5-15 三支柱完整化

**① 落点清单**

- 【修改】`control-app` OTel/Collector 配置面（`deploy/` 下 collector 配置——开工 `grep -rln 'otel\|collector' deploy/` 定位实际文件）：独立出口/独立凭据/资源上限。
- 【新增】低基数指标与风险 trace 保留的采样配置；应用侧如需打点在 `alert/infrastructure/selfcheck/` 同层新增。
- 不引入方案外新依赖（工序 3 纪律）。

**② 迁移**：无。**③ 接口契约**：无 HTTP；契约 = 遥测出口故障不影响业务状态（INV-AM5-8）。

**④ 验收**：本地 UT 面有限——Collector 配置静态校验脚本/装配测试【新增】；真证据 = E2E-AM5-08（切断 OTel/Collector 出口，遥测故障不改变业务/Run 状态），部署段执行。拆解验收原文同。

**⑤ 依赖**：M3-27~29（拆解原文，已收口）。**与 M5-11~14 全部可并行**（配置面，无代码冲突）。

### M5-16 控制面防自噬路由

**① 落点清单**

- 【新增】`alert/application/ControlAlertRouter.java`——入口二次判断：校验身份 + 允许的 AM route + monitoring_scope 白名单（**不信 webhook body 同名 label**——方案 §3.2 原文）；RCA_SYSTEM → 独立值班通道，**不创建 Incident/Run**。
- 【修改】`alert/interfaces/AlertWebhookController.java` 入链路挂二次判断（最小侵入，controller 不承载业务逻辑）。

**② 迁移**：无（route 白名单进配置/ConfigBundle 策略版本字段）。**③ 接口契约**：复用既有 webhook 入口；新增契约 = 控制面合成告警 → 独立值班通道投递记录，Incident/Run 增量为 0（INV-AM5-4）。

**④ 验收**：`mvn -B -ntp -pl control-app test -Dtest='ControlAlertRouterTest'`【新增】：伪造 label 拒绝/身份校验/白名单正反 UT；E2E-AM5-08 的"认证后 RCA_SYSTEM 告警只到独立值班通道且 Incident/Run 增量为 0"部署段执行。

**⑤ 依赖**：M5-15。M5-17 在其后。

### M5-17 2C4G Gatus 外部探针

**① 落点清单**

- 【新增】`deploy/gatus/`（config + compose 片段，2C4G 独立故障域，预算 96MiB——架构 §内存表已有）：黑盒 health + 合成 canary 告警；**不承载 RCA**。
- 本机无 Docker：本地只交付配置与静态校验；部署在 2C4G（**不是 195——交接纪律：M5 执行者不得触碰 195**）。

**② 迁移**：无。**③ 接口契约**：Gatus → 告警接收器 webhook（走 M5-16 独立值班通道）。

**④ 验收**：配置静态校验 + E2E-AM5-08"停 195 control endpoint，由 2C4G Gatus 独立探测，阈值内告警与恢复"（部署段）。拆解验收："195/control 挂时仍能独立告警"。

**⑤ 依赖**：M5-16。压力点 P-56（2C4G 机器可用性）开工时先确认环境。

---

## 阶段 E：数据生命周期与检索（M5-18~21）

### M5-18 RetentionPolicy 与月分区

**① 落点清单**

- 【新增】迁移 `V28__am5_monthly_partition.sql`、`V29__am5_retention_archive.sql`。
- 【新增】`ops/domain/model/RetentionPolicy.java`（版本化：月 RANGE 分区、legal hold、冷层位置）、`ops/domain/repository/RetentionPolicyRepository.java`、`ops/application/RetentionService.java`。
- 压力点 P-55：**月分区改造涉及 AM1~AM4 存量大表（rca_event/evidence/alert_inbox）**——M5-18 设计评审先行。

**② 迁移 DDL 要点（V28/V29）**

- V28：`rca_event`、`rca_evidence`、`alert_inbox` 转月 RANGE 分区（PG declarative partitioning；存量数据搬迁策略 = 新分区表+双写切换或离线回填，**开工时与 AM4 rca_event 写入路径核对后定**，列入设计评审议题）；**分区表唯一约束必须含分区键**（E-17 坑——`uq_rca_event_run_seq` 改造时必须把月分区键并入唯一约束，否则违约）；跨月查询结果不变是验收面。
- V29：`retention_policy`（id、policy_version、hot_retention、cold_location、legal_hold 布尔、created_at；版本化 insert-only）、`legal_hold`（scope、reason、created_by、released_at null=生效中）、`archive_manifest`（partition_name、row_count、content_digest、export_ref、state、created_at——导出条数/digest 校验的证据面）。

**③ 接口契约**：无 HTTP。不变量：legal hold 阻断一切清理（INV-AM5-9）。

**④ 验收**：`mvn -B -ntp -pl control-app test -Dtest='RetentionPolicyTest'`【新增】UT；IT（本机跳过）= 分区路由/跨月查询/legal hold 下 detach/delete 为 0（方案 §12.1 L2）。

**⑤ 依赖**：M5-01（拆解原文）。与阶段 B/C/D 可并行启动设计评审，但 V28 迁移落码建议排在阶段 D 收口后（避让 rca_event 写入面变更窗口）。

### M5-19 冷归档 manifest

**① 落点清单**

- 【新增】`ops/application/ArchiveService.java`——导出 → 条数/digest 校验 → `DETACH PARTITION CONCURRENTLY`；**失败不删热数据；legal hold 阻断**（方案 §3.2 原文）；pg_partman keep_table 为先例参照。
- 冷层存储形态（本地盘卷/对象存储）开工与 deploy/ 现状核对后定，列开放项 O-5。

**② 迁移**：无（表已在 V29）。**③ 接口契约**：归档作业内部 API；契约 = manifest 字段（partition/row_count/digest/export_ref/state）。

**④ 验收**：`mvn -B -ntp -pl control-app test -Dtest='ArchiveServiceTest'`【新增】：导出校验/失败不删热数据/legal hold 阻断 UT（外部副作用以 fake 冷层替身）；IT = 损坏包/冷层不可用演练（本机跳过）。E2E-AM5-09 脚本随件交付。

**⑤ 依赖**：M5-18。

### M5-20 历史假设检索 FTS 实验（默认关闭）

**① 落点清单**

- 【新增】`eval/domain/service/HypothesisSearchService.java`——FTS 只产 `UNTRUSTED_HYPOTHESIS`；**默认关闭**（feature flag）；跨租户/HOLDOUT 查询恒为 0（INV-AM5-9）。
- zhparser 中文分词现状未核实——**FTS 实验前补 spike**（方案 §7 残余风险②），spike 结论入 PROGRESS。

**② 迁移**：默认关闭→**本方案不占迁移号**；实验开门时再实测排号（tsvector 列+索引随实验评审冻结）。

**③ 接口契约**：`search(query, tenantCtx) -> List<Hypothesis{ref, score, trustLevel: UNTRUSTED_HYPOTHESIS}>`；不得仅凭历史命中发布根因（E2E-AM5-10 断言）。

**④ 验收**：`mvn -B -ntp -pl control-app test -Dtest='HypothesisSearchServiceTest'`【新增】：off 态零历史注入/on 态仅产 UNTRUSTED_HYPOTHESIS/跨租户·HOLDOUT 泄漏为 0 UT。

**⑤ 依赖**：M5-02、M5-06（拆解原文）。与 M5-18/19 可并行。

### M5-21 pgvector 可行性门

**① 落点清单**

- 【新增】`docs/` 不新建——可行性报告片段入 PROGRESS + 方案"问题与压力点"更新（工序纪律：发现后续依赖只记录在案）。
- 代码面：仅门控检查清单与指标采集脚本骨架【新增 `var/` 下或 `deploy/` 下，开工定】；**pgvector 本体默认不启用**（方案 §1"本期不做"原文："pgvector 默认不启用（M5-21 是可行性门不是建设任务）"）。

**② 迁移**：无。**③ 接口契约**：门 = recall/内存/延迟/备份数据齐全才接受（拆解验收列）；HNSW 内存估算需 195 实测——**195 当前被 AM4 E2E 并行会话独占，本任务实测段排在 195 释放后**（交接文档 §一/§六）。

**④ 验收**：可行性门清单逐项有数据或明确"未过门"结论；**未过门不启用**。

**⑤ 依赖**：M5-20。

---

## 收口：M5-22 AM5 G2

**① 落点**：无新代码；交付 = E2E-AM5-00~10 部署串联复跑（先跑 E2E-AM5-00 正常业务基线，再无跳过复跑 01~10——方案 §12.5 原文）、AA-26 契约证据包汇总（脱敏+sha256sums+机器可读断言，方案 §12.4）、G2 评审材料。

**② 硬门（方案 §12.3/§13 原文）**：E2E-AM5-00~10 无跳过并逐项归档证据；AM4 Shadow E2E 只能作前置资产不能替代；核心链含 mock/replay 必须标 HYBRID/REPLAY 不得宣称 LIVE（INV-AM5-11）；**真实 Native 候选若为 REJECT/INCONCLUSIVE，G2 可证明"门正确"但不得批准候选切流**；AM5 仅完成隔离 Canary 能力与演练，生产 1% Canary 归 M6-01。

**④ 验收**：拆解 §8 M5-22 验收列"安全、质量、运行、人工、归档、回退演练全绿"。**部署验证排在 M4 E2E 收口（195 释放）之后**（交接文档 §一）。

**⑤ 依赖**：M5-10~21（拆解原文）。

---

## 执行顺序总表（依赖拓扑 + 可并行批次）

| 批次 | 任务 | 说明 |
|---|---|---|
| B0 | M5-01 | 地基，独占先行（依赖 M4-38，已被用户豁免并行；开工先 `git pull --ff-only` 核对 HEAD + 迁移实测最大号） |
| B1 | M5-02 ∥ M5-03 ∥ M5-04 | 三者互不冲突（不同迁移/包面） |
| B2 | M5-05（←04） | 可与 B1 尾段搭接 |
| B3 | M5-06（←02,05）→ M5-07（←06）→ M5-08（←06,07） | 阶段 B 串行链 |
| B4 | M5-09（←08）→ M5-10（←09） | 阶段 C 串行链 |
| B5 | M5-11（←M4-37）∥ M5-15（←M3-27~29） | 与 B3/B4 全程可并行 |
| B6 | M5-12（←11）∥ M5-13（←M4-10/11） | 并行开发，联调合流 |
| B7 | M5-14（←12,13）；M5-16（←15）→ M5-17（←16） | |
| B8 | M5-18（←01，设计评审先行）→ M5-19（←18）；M5-20（←02,06）∥ | V28 避让 rca_event 写入面变更窗口 |
| B9 | M5-21（←20；195 实测段等 195 释放） | |
| B10 | M5-22（←10~21；195 释放后部署复跑） | G2 |

每完成一个任务：① `cat >>` 追加 `docs/告警-PROGRESS.md`（任务号+证据路径，只增不删）；② 有 Bug 当场追加 `docs/告警-BUGLOG.md`（编号接 BA-34 之后）；③ 再开始下一个任务（交接文档 §六工序 3 纪律原文）。

## DoD 清单（对齐方案 §13，执行者自检用）

1. M5-01~22 单项验收全过（拆解 §8 验收列 + 本方案各任务④）；L0~L5 分层矩阵全绿（本机 UT 基线 + 195/2C4G 部署段 IT/E2E 分开计证，**本机跳过的 IT 不计入完成证据**）。
2. INV-AM5-1~11 红绿留证（逐条对应测试/E2E 编号可回指）。
3. V20~V29 迁移契约 IT（真 PG 段）；insert-only/RLS/RLS 身份矩阵/原子激活/幂等合并/CAS/分区/legal hold 逐面有证。
4. AM7 前端联调面：`VITE_USE_MOCK=false` 下 P3/P4 页面（/rca-runs、事件流 SSE、Case 队列与命令、Run 命令）行为与 mock 期一致。
5. 证据归档 AA-26 契约；台账三件套同步；E2E-AM5-00~10 无跳过。
6. 真实 Native 候选 REJECT/INCONCLUSIVE 原样归档——**证明门正确即可，禁止批准切流**；生产 1% Canary 不越权（M6-01）。
7. 动工前本方案 V20 起编号实测复核留痕（find 命令输出入 PROGRESS）。

## 风险与开放项

**开放项（执行者开工时核对/裁定，不得编造）**：

- **O-1（M5-08 门禁记录持久化形态）**：`evaluation_record` 独立表 vs `eval_run` 扩展列未定——开工与 V10 schema 核对后定；若需新迁移号，先报用户确认再占号（守"一迁移一任务"与 INV-AM5-10）。
- **O-2（P5 评测查询/命令 API 无任务编号）**：前端 annot 明列的 `GET /eval/datasets|/eval/runs|/eval/compare|/eval/gates|/eval/review-queue|/eval/scorer-suites` 与"发起批量评测/发起发布门"两个命令 API，**拆解 §8 无对应编号**（annot 原文："正式方案须先在任务拆解增补编号"）。本方案不实现；AM7 前端联调缺口由用户裁定（增补拆解编号或排 AM7 后端配套），执行者不得擅自扩展。
- **O-3（Canary 与 AM4 Shadow 的关系）**：V25 唯一活跃索引调整为 `(incident_id, engine)` 粒度会改变 AM4 Shadow 并存前提——开工必须先核对 `Am4ShadowTrigger.java`（line 109 以 RERUN 铸造 Native run）与 `uq_rca_run_active_incident` 现行语义，迁移评审时坐实。
- **O-4（RBAC/用户体系落地形态）**：现状 0 用户体系（annot 缺口行）；Operator/ConfigBundle/命令 API 的 RBAC 与 stream ticket 签发件开工与架构 §17 核对后定最小实现。
- **O-5（冷层存储形态）**：归档冷层（本地卷/对象存储）与 deploy/ 现状核对后定。
- **O-6（V23 指纹落点）**：rca_attempt 增列 vs 侧表，见 M5-04②。

**残余风险（方案 §7 原列 + 交接新增）**：

1. **RCA-100 answer key 授权核查**（Redistribution 须联系维护方）——Adapter 接入前置；未授权走 fail-closed/NOT_AVAILABLE_AUTH，严禁伪造（E2E-AM5-02 原文）。
2. **费率校准**：litellm config.yaml 费率字段是 deepseek-v3 旧值占位（已标 TODO）——成本维记账与 per-EvalRun max_budget 硬拦依赖它，刊例价校准归 AM5 运维项（交接文档 §四）。
3. **eval 基线重建**：AM3"47 案例命中率 0"是 deepseek-v3 产出，qwen3.7-plus 下统计门禁基线须重测重建（交接文档 §四）；E2E-AM5-01 禁止把"命中率 0"改写成通过（方案 §12.3 原文）。
4. zhparser 未核实（FTS spike 前置）；HNSW 内存需 195 实测（195 释放后排期）；AM4 传导风险（M4-38 未签，豁免并行下的接口漂移——每任务开工核对 HEAD）。
5. 延期期间审批需求以人工台账兜底（OPA/WAITING_APPROVAL 延期裁定的配套，方案 §7 原文）。

## 修订记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-09-07 | v1.0 | 初稿：依技术方案 v1.2（G1 已签）+ 拆解 §8 + 执行交接文档起草；迁移编号按交接裁定从 V20 起重冻结（方案原 V19~V24 整体顺延+落码细化新增 V23/V25/V27/V29）；M5-12/13/14 API 契约按 wireframes v1.6 annot 与 AM7 前端 mock 写到字段级；登记矛盾点 C-1~C-4 与开放项 O-1~O-6 |

---

## 附：起草时发现的技术方案 v1.2 与代码现状矛盾点清单

- **C-1 迁移起点过时（已知裁定，本方案已重冻结）**：方案 §2/§7 INV-AM5-10 原文"AM5 自 V19 起"——实际 AM4 已占至 V19（`V19__am4_tool_replay.sql` 在库）；按方案自带顺延条款重排为 V20 起，方案原文下次修订时应同步。
- **C-2 rca_run 无 Canary 承载面（方案迁移清单未覆盖）**：方案 §4.1 要求"路由决策+config_digest 落 Run（固定不再变）"，但 `rca_run`（V7）无 engine/config_digest/stickiness/bucket 列，且 `uq_rca_run_active_incident` 唯一活跃索引与 Canary/Shadow 双活跃 Run 冲突；AM4 Shadow 当前以 `RunTrigger.RERUN` 复用 incident 铸造（`Am4ShadowTrigger.java:109`）。→ 落码新增 V25 承接，开工核对（O-3）。
- **C-3 attempt 粒度采样指纹无落点（方案迁移清单未覆盖）**：方案 §6 要求"采样指纹全字段落 Attempt 元数据"，但 `rca_attempt`（V7）无相关列；V10 的指纹字段只在 `eval_run` 头粒度。→ 落码新增 V23 承接（O-6）。
- **C-4 M5-14 命令持久化面无表（方案迁移清单未覆盖）**：方案 §3.2"命令先持久化再生效"无既有表可挂。→ 落码新增 V27 承接。
- **C-5（非矛盾，边界提醒）**：前端 annot 自述的评测查询/命令 API 超出拆解 §8 编号面——不是方案错误，但执行者若照前端 mock 全量实现即越权；已列 O-2 封存。

---

## 追加执行附录：AM5 E2E 与真实业务场景（v1.1，尾部追加）

> 本附录落实技术方案 v1.2 §12.2~12.5 与用户 2026-09-07“补充”裁定。它不修改 M5-01~22 编号、依赖或既有迁移表；与前文“M5-22 无新代码”等表述冲突时，以本附录为准：**业务功能无新增，但总控 runner、断言器和证据封装属于 M5-22 必交测试资产**。

### 一、E2E 资产与证据目录

延续 AM2/AM3/AM4 的阶段证据约定，AM5 使用以下固定布局：

```text
docs/测试证据/AM5/
├── e2e-脚本/
│   ├── e2e-am5-runall.sh                 # 依序执行 00~10；缺场景/SKIP/失败即非零退出
│   ├── e2e-am5-common.sh                 # 脱敏、轮询、只读 SQL、digest、资源账公共函数
│   ├── e2e-am5-preflight.sh              # 195+2C4G、版本、权限、预算和恢复能力预检
│   ├── e2e-am5-00-normal-baseline.sh
│   ├── e2e-am5-01-private-quality-gate.sh
│   ├── e2e-am5-02-public-adapters.sh
│   ├── e2e-am5-03-safety-gate.sh
│   ├── e2e-am5-04-config-bundle.sh
│   ├── e2e-am5-05-canary-rehearsal.sh
│   ├── e2e-am5-06-operator-case.sh
│   ├── e2e-am5-07-sse-command.sh
│   ├── e2e-am5-08-observability.sh
│   ├── e2e-am5-09-archive-restore.sh
│   └── e2e-am5-10-history-search.sh
├── fixtures/
│   ├── control-candidate/                 # 只证明 happy-path，严禁冒充 Native 质量
│   ├── redteam/                           # 注入/跨租户/越权/schema/write-intent
│   └── public-benchmark/                  # 仅 manifest/允许留存的公开输入与 digest
└── runs/<UTC批次>/
    ├── suite-manifest.json
    ├── scenario-results.json
    ├── assertions.json
    ├── sha256sums.txt
    ├── commands.log
    ├── topology/
    ├── sql/                               # 前后只读事实与副作用对拍
    ├── stats/                             # trial 原始行、cluster、CI、gate reasons
    └── raw/                               # 脱敏后的响应、事件、通知与组件日志
```

脚本只保留可执行内容，密钥、Bearer、数据库口令和环境变量 dump 禁止进入仓库/证据包。连接信息沿用既有安全配置注入；脚本输出必须先脱敏再落盘。公共 benchmark 的受限 answer key 不因测试方便复制进仓库。

### 二、总控 runner 契约

`e2e-am5-runall.sh` 必须满足：

1. 生成唯一 `suite_run_id`，创建本轮目录并记录 git commit、镜像 digest、模型/provider fingerprint、Dataset/ConfigBundle/threshold/adapter/stats 算法版本。
2. 先运行 preflight；195、2C4G Gatus、order-arena、Prometheus、Alertmanager、control-app、Holmes/Native、LiteLLM、PG 任一必需组件不满足时总体 `FAIL_PRECONDITION`，不得自动换 mock。
3. 固定先跑 E2E-AM5-00；正常业务基线失败时停止后续发布门测试，禁止用故障场景结果掩盖误报或业务回归。
4. 依序执行 01~10；每个脚本必须写一条 `scenario-results.json`，状态只允许 `PASS/FAIL/BLOCKED_EXTERNAL`。`SKIP/NOT_RUN/缺行` 一律总体失败。
5. `BLOCKED_EXTERNAL` 只允许 E2E-AM5-02 的 RCA-100 answer key 授权分支，且必须有授权核查记录；该分支只说明外部一致性未完成，不得生成通过结论。RCAEval 辅助回归与 Adapter fail-closed 测试仍必须执行。
6. 汇总前重新计算所有 artifact SHA-256；digest 不符、真实性标签缺失、trial 原始行缺失或机器断言缺失时总体失败。
7. 总控退出码：`0=全部必需场景通过`、`2=断言失败`、`3=环境/外部授权阻塞`、`4=证据不完整或篡改`。禁止捕获非零后强制返回 0。
8. runner 只清理本 `suite_run_id` 对应靶场开关、测试租户和临时数据；不得按模糊名称删除历史证据、生产数据或其他任务进程。

### 三、00~10 脚本责任与断言清单

| E2E | 随件任务 | 脚本必须驱动的真实边界 | 最小机器断言 |
|---|---|---|---|
| 00 正常基线 | M5-22（runner 骨架在 M5-08 时创建） | order-arena→业务依赖→Prometheus/Alertmanager 观测窗口 | 订单成功；Incident/Run/Claim/OperatorCase 增量 0；业务 SLO 与 Holmes 主路径无回归 |
| 01 私有质量门 | M5-01~08 | B1~B4 真实故障→Holmes/Native 同 Snapshot→EvalGateRunner→私有 HOLDOUT | GT 封存后才读；family 整组；采样/配置/引擎指纹齐；真实候选按数据 REJECT/INCONCLUSIVE/ELIGIBLE；未通过时 pointer/Canary 增量 0 |
| 02 公共 Adapter | M5-01/02/05/06 | RCA-100 v1.1/RCAEval 固定 manifest→Adapter→EvalCaseV1→辅助评分 | 来源九字段、artifact/digest、PUBLIC_BENCHMARK 身份；私有 HOLDOUT 查询 0；未授权 fail-closed 且不伪造 GT |
| 03 安全门 | M5-07（M5-08 复核接线） | 红队 fixture→真实 SafetyGate/QualityGate→ConfigBundle 激活入口 | 任一违规即 REJECT；pointer/Canary/外部写增量 0；五面原因和原始计数齐 |
| 04 ConfigBundle | M5-09 | CONTROL_FIXTURE→immutable bundle→active pointer CAS→在途 Run→回滚 | 无半激活；旧 Run 固定旧 digest；新 Run 取回滚 digest；历史 bundle 不改；结果标 CONTROL_FIXTURE |
| 05 隔离 Canary | M5-10 | order-arena 测试租户→CanaryRouter→Holmes/Native 隔离 Run→紧急回 Holmes | 同 key 恒桶、无 key 拒绝、超爆炸半径自动停；路由记录齐；零生产告警流量；回退只影响新 Run |
| 06 OperatorCase | M5-11/12 | B2/B3/B4→不确定/耗尽→Case 合并→双人并发 claim→ack/resolve/SLA | 单 fingerprint 单 Case；CAS 恰一成功；非法迁移拒绝；升级 outbox 恰一次；结案不改历史 Run |
| 07 SSE/命令 | M5-13/14 | B3 运行中 SSE→断网/重连/慢客户端→Cancel/Hint/Feedback | after_seq 无丢失和重复语义；断线不取消 Run；命令先落库且幂等；旧 revision/越权拒绝；Hint=UNTRUSTED |
| 08 观测防自噬 | M5-15~17 | 切断 Collector/OTel→真实业务继续→停 195 control→2C4G Gatus | 遥测故障不改业务/Run；RCA_SYSTEM 不建 Incident/Run；Gatus 在阈值内告警/恢复；独立值班通知可追 |
| 09 归档恢复 | M5-18/19 | 专用历史月分区→导出/校验/detach→恢复查询；损坏/不可用/legal hold 三分支 | 成功恢复逐行/digest 对拍；失败分支热数据不删；legal hold 下 detach/delete 增量 0；跨月查询不变 |
| 10 历史检索门 | M5-20/21 | B1/B2 历史事实→FTS off/on→pgvector 可行性采集 | off 零注入；on 仅 UNTRUSTED_HYPOTHESIS；跨租户/HOLDOUT 泄漏 0；历史命中不能直接发布；pgvector 未过门保持禁用 |

### 四、真实性与反伪造检查

每条场景必须写 `fidelity`：`LIVE_BUSINESS`、`HYBRID_BUSINESS`、`PUBLIC_REPLAY`、`CONTROL_FIXTURE` 四选一，并列出所有替代组件。总控按以下规则自动拒绝：

- 标 `LIVE_BUSINESS` 却出现 WireMock、REPLAY_MOCK、内存仓储或直写结果表。
- 标 `HYBRID_BUSINESS` 却没有列明 Logs/Change 等 replay 组件及 fixture digest。
- 将 `PUBLIC_REPLAY` 的 RCA-100/RCAEval 结果写入私有 HOLDOUT 主质量门，或单独驱动 ConfigBundle 激活。
- 将 `CONTROL_FIXTURE` 的 ELIGIBLE 结论作为 Native 质量证据。
- 真实 Native 为 REJECT/INCONCLUSIVE 时改写报告、丢弃失败 trial、重抽 seed 直到通过或移动阈值后不变更 ConfigBundle digest。
- 触发业务后直接 insert/update eval/gate/operator/canary 终态，或只验证 HTTP 200 不核对 PG 事实与副作用。

E2E-AM5-01 的真实候选与 E2E-AM5-04 的控制候选必须使用不同 `candidate_id/eval_run_id/bundle_digest/artifact` 目录；证据汇总器检测到交叉引用即失败。

### 五、随 M5-01~22 交付，不集中补票

| 阶段任务 | 当批测试资产 | 完成条件 |
|---|---|---|
| M5-01~03 | 01/02 数据夹具、manifest、family 分区与 GT 延迟授权断言 | Adapter/权限 IT + E2E 数据准备器可独立运行 |
| M5-04~08 | 01/03 runner、trial/stat/gate 机器断言；创建 runall 骨架和 00 基线脚本 | 真实候选/控制候选隔离，五分支裁决可读 |
| M5-09~10 | 04/05 runner | 原子激活/回滚、黏性、爆炸半径、生产流量为 0 |
| M5-11~14 | 06/07 runner | Case/SSE/命令全链通过，不以 Controller UT 替代 |
| M5-15~17 | 08 runner + 2C4G 操作配方 | 两故障域实跑，通知和 Incident/Run 零增量对拍 |
| M5-18~21 | 09/10 runner | 归档恢复与检索两态实跑，失败分支保热数据 |
| M5-22 | 完成 common/preflight/runall、无 skip 复跑 00~10、封存 AA-26 证据 | 缺脚本、缺场景、证据不全均不得申请 G2 |

已有任务完成代码不因本附录重做；其 E2E runner 通过公开入口验证真实接线。E2E 暴露的缺陷登记到对应 M5 任务与 BUGLOG，不新增 M5-23 规避权威拆解。

### 六、执行顺序与环境纪律

1. 开跑前确认 AM4 G2、195 AM4 E2E 已释放、2C4G Gatus 可达；记录两机时间、磁盘、内存、容器重启/OOM 和当前迁移最大号。
2. 对真实 LLM 调用设置本轮独立预算上限；LiteLLM/provider 不可达时失败留证，禁止改用 stub 继续标绿。
3. 依次执行 00→01→02→03→04→05→06→07→08→09→10；04/05 只作用于隔离测试租户，生产 1% Canary 属 M6-01。
4. 故障演练前记录恢复命令与准确目标；只停止明确的 Collector/control 测试实例，不扩大到无关进程或整机服务。
5. 每场结束核对未收敛 eval_run/operator_case/command/outbox、故障开关、active pointer、Canary 比例；除场景要求外不得留下活动状态。
6. 全套结束记录执行前/峰值/执行后资源、重启/OOM、归档空间和冷层恢复耗时；清理仅限本 `suite_run_id` 创建的临时对象。

### 七、M5-22/G2 一票否决项

- E2E-AM5-00~10 任一缺失、`SKIP/NOT_RUN`、无机器断言或无 SHA-256 清单。
- 195 真栈、2C4G Gatus、本机/真 PG IT 任一层缺证；某层通过不能替代另一层。
- 核心组件不可达后自动换 mock，或将 HYBRID/PUBLIC/CONTROL 证据标为 LIVE。
- 私有 HOLDOUT 泄漏给 Agent/RAG/调优身份，GT 在报告封存前可读，或同 family 被拆跨分区。
- 安全违规、真实质量门未过仍激活 bundle/产生 Canary 路由，或 AM5 接入生产 1% 流量。
- CONTROL_FIXTURE 与真实 Native 证据混用；RCA-100 未授权却伪造 answer key/外部一致性通过。
- OperatorCase 并发双认领、SSE 断线影响 Run、命令未先持久化、防自噬告警创建 Incident/Run。
- 归档失败删除热数据、legal hold 被绕过、FTS 产可信根因或 pgvector 未过门启用。

### 八、追加 DoD

1. `e2e-am5-runall.sh` 在 195+2C4G 完整运行，00~10 均有唯一结果和可复核证据；除 RCA-100 授权分支外不接受外部阻塞态。
2. `mvn clean verify`、V20~V29 真 PG 迁移/权限 IT、195+2C4G E2E 分别留非零执行证据。
3. B0~B4 至少各有一个真实 order-arena 入口样本；涉及 AM4 Logs/Change replay 时必须标 HYBRID 并绑定 fixture digest。
4. 真实 Native 裁决原样留存；REJECT/INCONCLUSIVE 可证明发布门正确，但 active pointer/Canary 增量必须为 0。
5. AA-26 证据、PROGRESS、BUGLOG 和任务完成状态一致后，方可申请 M5-22/G2；生产 1% Canary 仍须进入 M6-01。
