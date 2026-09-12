package com.objwww.pr.control.eval.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * UI-5/EV-03 评测查询投影只读端口（eval_run/eval_case_result/eval_phase_event(V80)/
 * dataset_version/case_version 五表投影；零写面——V45/V80 对 control_app 只授 SELECT）。
 *
 * <p>键集分页：runs 排序 (started_at DESC, id DESC)，cursor = 上一页末行的
 * (startedAt, runId)；cases 排序 (scenario_id ASC, round_no ASC)，cursor = 上一页
 * 末行的 (scenarioId, roundNo)——明文拼接/解析归应用服务，端口只收结构化游标。
 *
 * <p>诚实纪律（RunQueryService/IncidentQueryReader 同律）：
 * <ul>
 *   <li>RUNNING/FAILED run 的聚合指标（四率 + tp/fp/fn + 计数分子分母）DB 未回填 →
 *       如实 null，不回填 0；三件套装配（numerator/denominator/status）归应用服务；</li>
 *   <li>displayName/mode（V80 列）无写面回填 → 如实 null（EV-04 命令侧落值前不编造）；</li>
 *   <li>phase/phaseEnteredAt 投影自 eval_phase_event 最新事件，无事件 → null
 *       （§5.1：阶段由 worker 落事件，后端没有的阶段数据返回 null，不猜）；</li>
 *   <li>lastProgressAt = eval_case_result 最大落档时刻（真实进展信号），无案例 → null；</li>
 *   <li>caseExecutionId = eval_case_result.id（既有稳定 uuid 主键，RV02 复合 rowKey
 *       的唯一案例执行身份）；rcaRunId/scoredReportId 为既有外键直读（可空如实 null）；</li>
 *   <li>expected/actualRootCauseJson 为 jsonb 原文（{"component","fault_type","reason_code"}
 *       三元组快照，actual 可空），摘要字符串化归应用服务。</li>
 * </ul>
 */
public interface EvalQueryReader {

    /** runs 键集游标（(started_at, id) 严格小于继续取页） */
    record KeysetCursor(Instant at, UUID id) {
    }

    /** eval_run 投影行（指标/计数列可空 = 未终态化回填；名称/模式/阶段可空 = 无真实数据源；
     *  EV-04：recoveryState/terminalReason/cancelRequestedAt/launchPlanJson 直读 V81 列与
     *  eval_run_command 受理面——无数据源如实 null；EV-07：comparisonGateOutcome =
     *  本 run 作为候选的最新 eval_comparison 落档门结论（无落档如实 null，
     *  OK/VIOLATED/UNKNOWN 映射归应用服务）） */
    record EvalRunRow(UUID runId, String datasetVersion, String registryDigest, String model,
                      String promptVersion, String configDigest, String state,
                      Instant startedAt, Instant finishedAt,
                      Double coverage, Double conditionalAccuracy, Double endToEndHitRate,
                      Double unresolvedRate, Integer tp, Integer fp, Integer fn,
                      String displayName, String mode,
                      Integer totalScenarios, Integer decidableCount, Integer hitCount,
                      Integer unresolvedCount, long caseCount, Instant lastProgressAt,
                      String phase, Instant phaseEnteredAt,
                      String recoveryState, String terminalReason,
                      Instant cancelRequestedAt, String launchPlanJson,
                      String comparisonGateOutcome) {
    }

    /** 一页 runs；hasMore = 取到 limit+1 行（调用方据此发 nextCursor） */
    record EvalRunPage(List<EvalRunRow> items, boolean hasMore) {
    }

    /** eval_case_result 投影行（root cause/failureSample 为 jsonb 原文，摘要化归服务层） */
    record EvalCaseRow(UUID caseExecutionId, String scenarioId, int roundNo, String verdict,
                       boolean rootCauseHit, String expectedRootCauseJson,
                       String actualRootCauseJson, Long latencyMs, String failureSampleJson,
                       UUID rcaRunId, UUID scoredReportId) {
    }

    /** 一页 cases；hasMore 同 runs 惯例 */
    record EvalCasePage(List<EvalCaseRow> items, boolean hasMore) {
    }

    /** 数据集版本投影（caseCount=case_version 行数；families=去重 scenario_family_id，
     *  RLS 面下只计 control_app 可见的非 HOLDOUT 行；EV-08 起携带 name/sourceClass/
     *  partitionClass 数据集头身份列——dataset_version 无 RLS，元数据面全可见） */
    record DatasetRow(UUID datasetVersionId, String name, String version, String source,
                      String sourceClass, String partitionClass, long caseCount,
                      List<String> families, Instant createdAt) {
    }

    /**
     * 分区计数行（EV-08；case_version_partition_counts() security definer 针孔）：
     * 含 HOLDOUT 的真实计数——EV-08 卡"HOLDOUT 分区只出计数与元数据"的唯一来源；
     * 只出 (版本,分区,计数) 聚合，GT 原文与案例行内容永不进投影。
     */
    record PartitionCountRow(UUID datasetVersionId, String partitionClass, long caseCount) {
    }

    /** runs 列表页（state=null 不过滤；cursor=null 首页）。实现方内部取 limit+1 判 hasMore */
    EvalRunPage listRuns(String state, KeysetCursor cursor, int limit);

    /** run 详情行；未知 id → empty（controller 404 面） */
    Optional<EvalRunRow> findRun(UUID runId);

    /** cases 列表页（verdict=null 不过滤；afterScenario/afterRound=null 首页——
     *  (scenario_id, round_no) 严格大于继续取页） */
    EvalCasePage listCases(UUID runId, String verdict, String afterScenario,
                           Integer afterRound, int limit);

    /** 数据集版本全量（created_at DESC；数据集版本数为导入次数量级，不分页） */
    List<DatasetRow> listDatasets();

    /** 全数据集分区计数（EV-08 HOLDOUT 计数针孔；单查询，不 N+1） */
    List<PartitionCountRow> listPartitionCounts();

    // ------------------------------------------------------------------ EV-05 案例与证据

    /**
     * 案例详情行（EV-05 §3.4）：eval_case_result 全列 + 关联链直读（rca_run 状态/
     * incident/起止时刻、scored 报告结构验证面与 package_json 原文）。关联链任一环节
     * 缺席（verdict 形态决定 / 外键可空）如实 null；package_json 为 jsonb 原文
     * （::text 上抛），claims 解析与证据引用解析归应用服务。
     */
    record EvalCaseDetailRow(UUID caseExecutionId, UUID runId, String scenarioId, int roundNo,
                             String datasetVersion,
                             String selectionPolicyVersion, String verdict, boolean rootCauseHit,
                             String expectedRootCauseJson, String actualRootCauseJson,
                             String expectedSymptomCodesJson, String actualSymptomCodesJson,
                             Integer tp, Integer fp, Integer fn, Long latencyMs,
                             boolean silencePenalty, String failureSampleJson, Instant createdAt,
                             UUID rcaRunId, UUID scoredAttemptId, UUID scoredReportId,
                             String rcaRunState, UUID incidentId,
                             Instant rcaStartedAt, Instant rcaFinishedAt,
                             Integer reportSchemaVersion, String reportValidationStatus,
                             String reportModel, Instant reportCreatedAt, String packageJson) {
    }

    /**
     * 场景身份解析行（EV-05 §3.4）：case_version 精确键匹配
     * （dataset_version.version = run.dataset_version 且 case_key = scenario_id——
     * 声明式键匹配，不按时间猜归属；无匹配/HOLDOUT 行 RLS 不可见 → empty 如实）。
     * 只投影身份/摘要/分区字段，payload（GT 原始 artifact）不出读面。
     */
    record CaseIdentityRow(String caseKey, String scenarioFamilyId, String contentDigest,
                           Instant validFrom, Instant validTo, String partitionClass,
                           String datasetName, String datasetVersion, String sourceClass) {
    }

    /**
     * Run 证据汇总扁平行（EV-05）：每行 = 一个案例的一条 claim 的一条 evidence_ref
     * （SQL 侧只抽取小字段，不上抛 package 大文本；claims 缺/非数组面由 SQL CASE
     * 护住）。ref 可解析为案例所属 rca_run 范围内的 rca_evidence 行 → evidenceId/
     * evidenceType 直读（跨 run 引用不解析，bucket 归 UNRESOLVED 由服务层判）；
     * 无报告/无 claim/无 ref 的案例出一行全空哨兵（claimStatus=null），
     * 服务层据此区分"无报告"与"报告无引用"。
     */
    record CaseEvidenceRefRow(UUID caseExecutionId, String scenarioId, int roundNo,
                              String verdict, UUID rcaRunId, UUID scoredReportId,
                              String claimStatus, String claimType, String ref,
                              UUID evidenceId, String evidenceType) {
    }

    /**
     * 受限日志比较证据行（EV-05 §3.4/§5.3 末行）：案例关联 rca_run 的 logs.query
     * 冻结证据（payload 为 EX-B2 统一形状原文；时间窗/服务从证据行本身取——
     * 端点不接受自由查询参数，不发新 Loki 查询）。
     */
    record CaseLogEvidenceRow(UUID caseExecutionId, String scenarioId, int roundNo,
                              UUID rcaRunId, UUID evidenceId, String source, String scopeJson,
                              Instant timeStart, Instant timeEnd, String payloadJson,
                              String payloadDigest, Instant evidenceCreatedAt) {
    }

    /** rca_evidence 元数据行（详情面证据引用解析；payload 大文本不上抛，只带 digest） */
    record EvidenceMetaRow(UUID evidenceId, UUID runId, String evidenceType, String source,
                           String scopeJson, Instant timeStart, Instant timeEnd,
                           String payloadDigest, Instant createdAt) {
    }

    /** 案例详情（runId + caseExecutionId 双键定位，防跨 run 直读）；未知 → empty */
    Optional<EvalCaseDetailRow> findCaseDetail(UUID runId, UUID caseExecutionId);

    /** 场景身份精确键解析（见 CaseIdentityRow 注释）；无匹配 → empty */
    Optional<CaseIdentityRow> findCaseIdentity(String datasetVersion, String scenarioId);

    /** run 全案例的证据引用扁平行（单查询，不 N+1）；run 无案例 → 空表 */
    List<CaseEvidenceRefRow> listCaseEvidenceRefs(UUID runId);

    /** 案例所属 rca_run 范围内的证据元数据批量读（id 白名单 + rca_run 双约束，
     *  跨 run id 不返回——§3.4 禁止凭任意 evidenceId 跨对象读取） */
    List<EvidenceMetaRow> listEvidenceMeta(UUID rcaRunId, List<UUID> evidenceIds);

    /** 指定 run + scenario 的案例关联 logs.query 冻结证据（created_at, evidenceId 序） */
    List<CaseLogEvidenceRow> listCaseLogEvidence(UUID runId, String scenarioId);

    // ------------------------------------------------------------------ EV-07 配对工作台

    /**
     * 对比用 run 元数据行（EV-07 §3.5 可比性检查输入）：eval_run 十项可复现元数据中
     * 参与严格一致判定的维度直读（数据集版本/输入快照 registry_digest/规则版本
     * alert_rule_digest/同义词典/场景驱动）+ 信息面维度（model/prompt_version——
     * 模型差异正是对比动机，不参与严格判定）。无 grader 版本列（偏差如实：
     * 评分器语义锚 = 逐案例 selection_policy_version，见 CompareCaseRow）。
     */
    record CompareRunMeta(UUID runId, String datasetVersion, String registryDigest,
                          String alertRuleDigest, Integer lexiconVersion,
                          String scenarioDriverVersion, String model, String promptVersion,
                          String configDigest, String state) {
    }

    /**
     * 对比用案例投影行（EV-07 配对输入）：eval_case_result 逐案例判定面 +
     * case_version 身份列（content_digest/scenario_family_id）经精确键横向解析
     * （dv.version = run.dataset_version 且 case_key = scenario_id；无匹配/歧义/
     * HOLDOUT RLS 不可见 → 身份列 null，与 EV-05 findCaseIdentity 同律不猜）。
     * rca_run_id/latency_ms（R12 逐例差值输入）：费用差经 rca_run_id 链 usage
     * 投影（R6 同源），延迟差直读用例列。
     */
    record CompareCaseRow(UUID caseExecutionId, String scenarioId, int roundNo,
                          String verdict, boolean rootCauseHit,
                          String expectedRootCauseJson, String selectionPolicyVersion,
                          String contentDigest, String scenarioFamilyId,
                          UUID rcaRunId, Long latencyMs) {
    }

    /** 对比 run 元数据；未知 id → empty（controller 404 面） */
    Optional<CompareRunMeta> findCompareMeta(UUID runId);

    /**
     * run 全量对比案例行（scenario_id ASC, round_no ASC 稳定序；limit 为硬扫描闸——
     * 调用方传上限+1 判 truncated，超出行不得进入统计）。run 无案例 → 空表。
     */
    List<CompareCaseRow> listCasesForCompare(UUID runId, int limit);

    // ------------------------------------------------------------------ R6/EV-06 usage 投影

    /**
     * eval run 关联的全部已结算模型调用行（逐调用原样返回，分组聚合归应用面）。
     * 身份链 = eval_case_result.rca_run_id 显式映射（RV08 红线：不碰 PR 域平台
     * 模型账本——RCA 唯一账本山是 rca_model_call；不按时间窗猜归因——EU17 结构性隔离）。
     */
    List<UsageCallRow> listUsageCalls(UUID evalRunId);

    /**
     * 批量面（EV-06 列表接线，禁 N+1）：多 run 的已结算调用行一次取回，
     * 按 run 分组归应用服务。evalRunIds 为空 → 空表（不拼 IN ()）。
     */
    List<UsageCallRow> listUsageCallsForRuns(Iterable<UUID> evalRunIds);

    /** usage 调用行（usage/cost 仅 SUCCESS 带回报行可能在场；pricingVersion 含
     * 'unpriced' 显式态——R4 契约，与 usage 缺失可区分） */
    record UsageCallRow(UUID evalRunId, UUID rcaRunId, UUID attemptId, String roleId,
                        String state, Integer promptTokens, Integer completionTokens,
                        Integer totalTokens, Long costMicros, String pricingVersion,
                        String currency, boolean usageMissing) {
    }

    // ------------------------------------------------------------------ A3 阶段事件读面（§5.3 events 端点）

    /** eval_phase_event 投影行（V80 全列减去 created_at；detail 为 jsonb ::text 原文
     *  上抛——事件自有载荷白名单透传，解析归应用服务。表无 seq 列，游标走
     *  (entered_at, id) 键集，不照抄 rca_event 的 after_seq 数字游标） */
    record EvalPhaseEventRow(UUID id, String phase, Instant enteredAt, String workerId,
                             String detail) {
    }

    /** 一页阶段事件；hasMore 同 runs 惯例（实现方内部取 limit+1 判） */
    record EvalPhaseEventPage(List<EvalPhaseEventRow> items, boolean hasMore) {
    }

    /** run 阶段事件页（(entered_at, id) 严格大于续页，升序；cursor=null 首页） */
    EvalPhaseEventPage listPhaseEvents(UUID runId, KeysetCursor cursor, int limit);
}
