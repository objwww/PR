package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
import com.objwww.pr.control.alert.domain.model.ReportClaim;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CaseEvidenceRefRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CaseIdentityRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CaseLogEvidenceRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCaseDetailRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvidenceMetaRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCasePage;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCaseRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalPhaseEventPage;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalPhaseEventRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalRunPage;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalRunRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.DatasetRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.KeysetCursor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * UI-5/EV-03 评测只读查询投影服务（/api/eval/** 面；SQL 归 {@link EvalQueryReader}，
 * 本类只承担游标编解码、state/verdict 参数校验、比率三件套与状态分面装配、
 * root cause/failureSample 的 jsonb 摘要字符串化——纯函数段，假端口可测，
 * 沿 IncidentQueryService 单测模式）。
 *
 * <p>游标明文拼接（沿 IncidentQueryService 惯例）：runs = {@code <startedAt-ISO>/<runId>}；
 * cases = {@code <scenarioId>/<roundNo>}（scenarioId 可含 "/"，按最后一个 "/" 切分）。
 *
 * <p>EV-03 契约扩展（改造方案 §5.1/§5.2/§5.3 第一行）：
 * <ul>
 *   <li><b>null 语义（§5.2）</b>：新比率一律 numerator/denominator/status 三件套——
 *       分母 0 → NOT_APPLICABLE（原始计数照报）；分子分母未回填（RUNNING/FAILED）→
 *       UNKNOWN 且两值 null（不填 0 冒充）。旧数值字段（coverage 等 double）原样保留，
 *       存量序列化契约不静默改；</li>
 *   <li><b>状态分面（§5.1）</b>：executionState（= 旧 state，同义别名）/phase/
 *       qualityVerdict/recoveryState/usageStatus/freshness 分开表达。phase 投影自
 *       eval_phase_event（V80）最新事件，无事件如实 null；stageEnteredAt、
 *       lastProgressAt（= 最新案例落档时刻）、leaseHeartbeatAt 是三个不同时间戳——
 *       租约心跳无数据源（EV-04 前）如实 null；</li>
 *   <li><b>未接线分面不编造</b>：usageStatus/costStatus 自 EV-06 起接真值（R7 RCA
 *       调用账本 rca_model_call 沿 rca_run_id 身份链聚合，RV08 红线：PR 域模型调用账本的
 *       review_run_id 指向 PR review_run，严禁用它汇总 eval/RCA 用量——rollup 规则见
 *       RunUsageRollup）；qualityVerdict 自 EV-07 起接真值
 *       （本 run 作为候选的最新 eval_comparison 落档门结论，见 facets 装配注释）；
 *       recoveryState/cancelRequestedAt 自 EV-04 起有真值
 *       （V81 列 + eval_run_command 受理面，见 facets 装配注释）；</li>
 *   <li><b>asOf</b>：投影同步直读主表，asOf = 请求处理时刻，freshness=LIVE；
 *       列表与详情同口径；</li>
 *   <li><b>案例执行身份（§5.1/RV02）</b>：caseExecutionId = eval_case_result.id
 *       （稳定 uuid），并直读 rcaRunId/scoredReportId 关联链（可空如实 null）。</li>
 * </ul>
 * EV-05 扩展（§3.4/§5.3）：案例详情（双键定位 + 场景身份精确键解析 + 报告支持/
 * 反对证据引用解析，跨 rca_run 引用不解析）、Run 证据汇总（按案例分组计数/类型分桶，
 * 无报告如实 NO_REPORT）、受限日志比较（冻结 logs.query 证据签名 diff，不收自由
 * 查询参数）——见 caseDetail/evidenceSummary/logCompare。
 * 六维分析/评分器/发布门无持久化数据，本服务不开对应端点（前端空态明示）。
 */
public class EvalQueryService {

    private static final Set<String> RUN_STATES = Set.of("RUNNING", "SUCCEEDED", "FAILED");
    private static final Set<String> VERDICTS = Set.of("DECIDABLE", "UNRESOLVED",
            "STRUCTURE_REJECTED", "TIMEOUT_OR_ABSENT");

    /** 分面状态词表：OK=有真实数据；VIOLATED=质量门未过（EV-07 起）；
     *  NOT_APPLICABLE=分母 0；UNKNOWN=未回填/未接线；USAGE_MISSING/UNPRICED =
     *  EV-06 用量/价目显式态（R4/EU19 契约——绝不把未知并成 0） */
    private static final String STATUS_OK = "OK";
    private static final String STATUS_VIOLATED = "VIOLATED";
    private static final String STATUS_NOT_APPLICABLE = "NOT_APPLICABLE";
    private static final String STATUS_UNKNOWN = "UNKNOWN";
    private static final String STATUS_USAGE_MISSING = "USAGE_MISSING";
    private static final String STATUS_UNPRICED = "UNPRICED";

    /** 同步直读主表 = 投影无滞后（asOf 即请求时刻） */
    private static final String FRESHNESS_LIVE = "LIVE";

    private final EvalQueryReader reader;
    private final ObjectMapper mapper;
    private final EvalRubricRegistry rubrics;

    public EvalQueryService(EvalQueryReader reader, ObjectMapper mapper,
                            EvalRubricRegistry rubrics) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.rubrics = Objects.requireNonNull(rubrics, "rubrics");
    }

    // ------------------------------------------------------------------ DTO（record，字段名即 JSON 契约）

    /**
     * 比率三件套（§5.2）：numerator/denominator 为原始计数；status ∈
     * OK / NOT_APPLICABLE（分母 0）/ UNKNOWN（未回填，两值 null）。不填 0 冒充未知。
     */
    public record RatioStat(Long numerator, Long denominator, String status) {
    }

    /**
     * 质量分面（§5.2 指标词典的投影面）：四率沿 ScenarioMetrics 原公式 + 错误确认
     * （可判定但根因错误 = decidable − hit，与症状 FP 分开命名）。
     */
    public record QualityFacet(RatioStat coverage, RatioStat conditionalAccuracy,
                               RatioStat endToEndHitRate, RatioStat unresolvedRate,
                               RatioStat falseConfirmation) {
    }

    /**
     * EV-09 稳定性分面（Agent 非确定性一级指标——业界共识：IBM ITBench
     * run-to-run consistency / Majority-at-k、RCAEval Avg@5；单轮命中率会被
     * 幸运轮抬走，稳定性必须独立计量）。D01（F09 修正）指标命名与分母口径：
     * <ul>
     *   <li><b>passAt1 = micro 逐轮成功率</b>（命中轮次/已落档轮次，逐轮等权）——
     *       与各场景等权的 macro 不是同一指标，不可混称同一个 pass@1；</li>
     *   <li><b>macroPassRate = macro 场景等权成功率</b>（各场景命中率的算术平均，
     *       场景轮数不同时与 micro 必然偏离，故分称）；</li>
     *   <li><b>passAllRounds = 全部计划轮次成功场景/场景数</b>（历史字段名保留兼容；
     *       语义是"全轮命中"即 pass^k 口径——只有固定 k 且全部计划 trial 终态完整
     *       （planComplete=true 且 plannedRoundsPerScenario 非 null）时才允许以
     *       pass^k 命名展示，少轮/计划身份缺失时只是暂态观测，不构成通过结论）；</li>
     *   <li><b>passAtLeastOnce = k 次至少一次成功场景/场景数</b>（通行 pass@k 本义，
     *       实测比例直展，不假设 trial 独立、绝不用 p^k 冒充实测）；</li>
     *   <li><b>scenarioConsistency = 轮间一致性</b>（判定与实际根因三元组全轮一致的
     *       场景/场景数，与对错正交——稳定地答错也一致，一致性单独不代表质量）；</li>
     *   <li><b>roundsProgress / plannedRoundsPerScenario / planComplete</b>：冻结
     *       launch plan 计划轮次对照面——plannedRounds 逐场景读自 launch_plan 快照
     *       （roundsPerScenario，空值 = worker 默认 2；回放形态场景按 runner
     *       effectiveRounds 同律裁剪为 1），对照实际终态落档轮次。运行中展示进度
     *       与暂态结果；计划快照不可用/缺 caseKeys 身份 → 三面 null/UNKNOWN，
     *       不猜计划、不出完整性结论。</li>
     * </ul>
     * 无案例落档 → 各比率 UNKNOWN，不填 0。
     */
    public record StabilityFacet(RatioStat passAt1, RatioStat passAllRounds,
                                 RatioStat scenarioConsistency,
                                 RatioStat passAtLeastOnce,
                                 MacroStat macroPassRate,
                                 RatioStat roundsProgress,
                                 Integer plannedRoundsPerScenario,
                                 Boolean planComplete) {
    }

    /**
     * macro 平均率（D01：各场景等权成功率——非整数计数比，RatioStat 三件套表达不了
     * 算术平均，单列 value ∈ [0,1] 实测均值；samples = 参与平均的场景数；
     * 无场景落档 → UNKNOWN 且 value null）。
     */
    public record MacroStat(Double value, Long samples, String status) {
    }

    /**
     * 状态分面（§5.1）：各面分开表达，互不顶替。leaseHeartbeatAt 无租约数据源 → null；
     * usageStatus/costStatus 自 EV-06 起接真值（rca_model_call 沿 rca_run_id 身份链
     * 聚合，RunUsageRollup；无 rca 链/无已结算调用 → UNKNOWN）；EV-04 起
     * recoveryState/cancelRequestedAt 有真值（见 facets 装配）。
     */
    public record RunFacets(String executionState, String phase, Instant stageEnteredAt,
                            Instant lastProgressAt, Instant leaseHeartbeatAt,
                            String qualityVerdict, String recoveryState,
                            String usageStatus, String costStatus, String freshness,
                            Instant cancelRequestedAt, Long costMicros, String currency) {
    }

    /**
     * run 级用量/价目 rollup（EV-06 列表接线；逐调用三态归 usageStatusOf，
     * run 级取最坏态——任何一笔 usage_missing 即整 run 用量未知，任何一笔
     * unpriced 即整 run 费用未定价。无 rca 链/无已结算调用 → 双 UNKNOWN 如实）。
     */
    record RunUsageRollup(String usageStatus, String costStatus, Long costMicros, String currency) {
        static final RunUsageRollup UNKNOWN_FACETS =
                new RunUsageRollup(STATUS_UNKNOWN, STATUS_UNKNOWN, null, null);

        static RunUsageRollup of(List<EvalQueryReader.UsageCallRow> rows) {
            if (rows.isEmpty()) {
                return UNKNOWN_FACETS;
            }
            boolean usageMissing = false;
            boolean unpriced = false;
            long costMicros = 0L;
            String currency = null;
            for (EvalQueryReader.UsageCallRow row : rows) {
                String s = usageStatusOf(row);
                usageMissing |= "usage_missing".equals(s);
                unpriced |= "unpriced".equals(s);
                if (row.costMicros() != null) {
                    costMicros += row.costMicros();
                    if (currency == null) {
                        currency = row.currency();
                    }
                }
            }
            if (usageMissing) {
                return new RunUsageRollup(STATUS_USAGE_MISSING, STATUS_UNKNOWN, null, null);
            }
            return unpriced ? new RunUsageRollup(STATUS_OK, STATUS_UNPRICED, null, null)
                    : new RunUsageRollup(STATUS_OK, STATUS_OK, costMicros, currency);
        }
    }

    public record EvalRunListItem(UUID runId, String datasetVersion, String registryDigest,
                                  String model, String promptVersion, String configDigest,
                                  String state, Instant startedAt, Instant finishedAt,
                                  Double coverage, Double conditionalAccuracy,
                                  Double endToEndHitRate, Double unresolvedRate,
                                  Integer tp, Integer fp, Integer fn,
                                  Double precision, Double recall, Double f1,
                                  String displayName, String mode,
                                  long caseCount, Integer totalScenarios,
                                  QualityFacet quality, StabilityFacet stability,
                                  RunFacets facets,
                                  long modelCallFailures, String modelCallFailureCode,
                                  RatioStat sixPartsRate,
                                  String terminalReason) {
    }

    public record EvalRunListResponse(List<EvalRunListItem> items, String nextCursor,
                                      Instant asOf) {
    }

    public record EvalRunDetailResponse(UUID runId, String datasetVersion, String registryDigest,
                                        String model, String promptVersion, String configDigest,
                                        String state, Instant startedAt, Instant finishedAt,
                                        Double coverage, Double conditionalAccuracy,
                                        Double endToEndHitRate, Double unresolvedRate,
                                        Integer tp, Integer fp, Integer fn,
                                        Double precision, Double recall, Double f1,
                                        long caseCount,
                                        String displayName, String mode, Integer totalScenarios,
                                        QualityFacet quality, StabilityFacet stability,
                                        RunFacets facets, Instant asOf,
                                        String terminalReason, JsonNode launchPlan,
                                        RatioStat sixPartsRate) {
    }

    /** 案例列表项（P3：三维评分+过程计数可空直读——null=未评/无检查点，不填 0；
     *  promptTokens/completionTokens/totalTokens = 本案例模型调用 token 聚合
     *  （rca_model_call 经 rca_run_id 身份链，scored_attempt_id 在场即按被评分 attempt
     *  聚合）；无模型调用记录 → 三值 null 如实） */
    public record EvalCaseItem(UUID caseExecutionId, String scenarioId, int roundNo,
                               String verdict, Boolean rootCauseHit, String expectedRootCause,
                               String actualRootCause, Long latencyMs, String failureSample,
                               UUID rcaRunId, UUID scoredReportId,
                               Boolean causeComponentHit, Boolean causeFaultHit,
                               Boolean causeReasonHit, Integer checkpointsTotal,
                               Integer checkpointsCovered, String checkpointMatches,
                               String conclusionGrounded, Integer toolCallsTotal,
                               Integer toolCallsUnique, String difficulty,
                               Long promptTokens, Long completionTokens, Long totalTokens) {
    }

    public record EvalCaseListResponse(List<EvalCaseItem> items, String nextCursor) {
    }

    // ------------------------------------------------------------------ A3 阶段事件 DTO

    /** 阶段事件项（A3/§5.3 events 读面；detail 为事件自有 jsonb 载荷原文解析透传，
     *  无/解析失败如实 null——白名单：非证据正文，不进 canonicalPayload 纪律） */
    public record EvalPhaseEventItem(UUID id, String phase, Instant enteredAt,
                                     String workerId, JsonNode detail) {
    }

    /** 一页阶段事件；nextCursor = 末行 (enteredAt|eventId) 键集明文，末页 null */
    public record EvalPhaseEventListResponse(List<EvalPhaseEventItem> items,
                                             String nextCursor, Instant asOf) {
    }

    /**
     * 数据集列表项（EV-08 增强；§3.6 数据集列表）：名称/版本/来源/分区身份 +
     * 案例计数。caseCount = RLS 可见行计数（非 HOLDOUT）；partitionCounts =
     * 全分区真实计数（HOLDOUT 只出计数，security definer 针孔，GT 原文永不进
     * 投影——EV-05 裁定同律）；rubricVersion = 当前生效评分细则版本（EV-08 卡
     * "rubric 版本随数据集版本透出"）。旧字段（version/source/caseCount/families/
     * createdAt）原样保留，存量序列化契约只增不改。
     */
    public record DatasetItem(String name, String version, String source,
                              String sourceClass, String partitionClass,
                              long caseCount, Map<String, Long> partitionCounts,
                              List<String> families, String rubricVersion,
                              Instant createdAt) {
    }

    public record DatasetListResponse(List<DatasetItem> items, Instant asOf) {
    }

    /** 数据集详情（EV-08）：列表项全量 + 已知 rubric 版本全表（冻结版本历史） */
    public record DatasetDetailResponse(DatasetItem dataset,
                                        List<String> knownRubricVersions, Instant asOf) {
    }

    /** 数据集案例清单项（案例浏览器读面）：身份 + 策划中文描述 + 期望根因摘要 +
     *  期望症状码；payload 原文大字段不上抛 */
    public record DatasetCaseItem(String caseKey, String scenarioFamilyId,
                                  String partitionClass, String note,
                                  String expectedRootCause,
                                  List<String> expectedSymptomCodes) {
    }

    public record DatasetCaseListResponse(String name, String version,
                                          List<DatasetCaseItem> items, Instant asOf) {
    }

    // ------------------------------------------------------------------ EV-05 DTO（案例详情 / Run 证据汇总 / 受限日志比较）

    /** 证据引用解析项（EV-05 §3.4）：ref 原文 + 解析面。resolved=false = 非 UUID 形态
     *  或不在案例所属 rca_run 范围内（跨对象不读，元数据全 null 如实） */
    public record ResolvedEvidenceRef(String ref, boolean resolved, UUID evidenceId,
                                      String evidenceType, String source,
                                      Instant timeStart, Instant timeEnd, String payloadDigest) {
    }

    /** 断言级证据项：claim 身份 + 其证据引用列表 */
    public record ClaimEvidenceItem(String claimType, String component, String faultType,
                                    List<ResolvedEvidenceRef> refs) {
    }

    /** 报告摘要面（package_json 的人读段；references = artifact_ref 白名单引用） */
    public record ReportSummary(String summary, String impact, String remediation,
                                List<String> references) {
    }

    /**
     * 证据块（EV-05 §3.4）：status ∈ OK / NO_REPORT（verdict 形态决定无评分报告）/
     * UNSUPPORTED_SCHEMA_VERSION（非 v2 包，不猜结构）/ PACKAGE_UNPARSEABLE
     * （v2 形状校验失败——写期已验证的包读回解析失败显式标记，不当空吞）。
     * 支持=status TRUE 的断言引用；反对=FALSE；undetermined=UNKNOWN。
     */
    public record CaseEvidenceBlock(String status, List<ClaimEvidenceItem> supporting,
                                    List<ClaimEvidenceItem> refuting,
                                    List<ClaimEvidenceItem> undetermined) {
    }

    /** 场景身份（EV-05 §3.4）：resolved=false = 精确键无匹配/歧义/HOLDOUT RLS 不可见，
     *  身份字段全 null 如实（datasetVersion 恒为 run 直读值，不依赖解析）；
     *  unresolvedReason（BA-169）：resolved=true → null；否则 AMBIGUOUS（归属歧义）
     *  或 NO_MATCH_OR_HOLDOUT（无可见匹配，安全纪律不区分两者）；
     *  matchedDatasets：歧义时 = 匹配到的 datasetName 列表，否则空表 */
    public record ScenarioIdentity(String scenarioId, String datasetVersion, boolean resolved,
                                   String caseKey, String scenarioFamilyId, String contentDigest,
                                   String partitionClass, String datasetName, String sourceClass,
                                   Instant validFrom, Instant validTo,
                                   String unresolvedReason, List<String> matchedDatasets) {
    }

    /** 关联链块（EV-03 关联链的详情展开；环节缺席如实 null） */
    public record LinkageBlock(UUID rcaRunId, String rcaRunState, UUID incidentId,
                               Instant rcaStartedAt, Instant rcaFinishedAt,
                               UUID scoredAttemptId, UUID scoredReportId,
                               Integer reportSchemaVersion, String reportValidationStatus,
                               String reportModel, Instant reportCreatedAt) {
    }

    public record EvalCaseDetailResponse(UUID caseExecutionId, UUID runId, String scenarioId,
                                         int roundNo, String selectionPolicyVersion,
                                         String verdict, boolean rootCauseHit,
                                         String expectedRootCause, String actualRootCause,
                                         List<String> expectedSymptomCodes,
                                         List<String> actualSymptomCodes,
                                         Integer tp, Integer fp, Integer fn, Long latencyMs,
                                         boolean silencePenalty, String failureSample,
                                         Instant createdAt, ScenarioIdentity scenarioIdentity,
                                         LinkageBlock linkage, ReportSummary report,
                                         CaseEvidenceBlock evidence,
                                         List<CaseBehaviorEntry> behavior,
                                         List<CaseLoopEntry> loop,
                                         List<CaseCollabEntry> collab,
                                         List<CaseDriftEntry> drift, Instant asOf) {
    }

    /** 行为评测覆盖双轨（ME-T04/D04；各轨可空 = 该轨未评如实，不填 0） */
    public record CaseBehaviorCoverage(Integer textCovered, Integer textTotal,
                                       Integer evidenceCovered, Integer evidenceTotal) {
    }

    /** 行为评测单项检查（status = 五态名原文，中文映射归前端字典；reasonCode 裸码
     *  就近标注成因归前端） */
    public record CaseBehaviorCheck(String name, String status, String reasonCode,
                                    List<String> evidenceRefs) {
    }

    /** 行为评测指标分子/分母（分母 0 = 口径内无对象如实） */
    public record CaseBehaviorMetric(String name, long numerator, long denominator) {
    }

    /**
     * 案例行为评测条目（ME-T04/V160；一案例一 grader 版本一条，多版本并存如实并列）。
     * jsonb 解析失败的行如实缺席（不猜——与 safetySummary 违规明细同律）。
     */
    public record CaseBehaviorEntry(String graderVersion, String traceDigest,
                                    CaseBehaviorCoverage coverage,
                                    List<CaseBehaviorCheck> checks,
                                    List<CaseBehaviorMetric> metrics,
                                    List<String> failureLabels,
                                    List<String> evidenceRefs) {
    }

    /**
     * 案例死循环评测条目（ME-T12/V162；一案例一 grader 版本一条，多版本并存如实
     * 并列）。观测标量可空 = 观测读失败 ERROR 行或数据缺失如实不出数；jsonb 解析
     * 失败的行如实缺席（不猜——与 behaviorEntries 同律）。
     */
    public record CaseLoopEntry(String graderVersion, String stopReason,
                                Integer detectionEventIndex,
                                Integer firstNoProgressEventIndex,
                                int postStopNewActions, Long physicalCallsFromOnset,
                                Long tokensFromOnset, Long secondsFromOnset,
                                List<CaseBehaviorCheck> checks,
                                List<CaseBehaviorMetric> metrics,
                                List<String> failureLabels) {
    }

    /**
     * 案例协作评测条目（ME-T12a/V163；一案例一 grader 版本一条，多版本并存如实
     * 并列）。观测标量可空 = 观测读失败 ERROR 行或 trace 缺失如实不出数；归因
     * 双轨（suspected/supported）分列不混；jsonb 解析失败的行如实缺席（不猜）。
     */
    public record CaseCollabEntry(String graderVersion, Integer edgeCount,
                                  Integer admittedCount, Long tokenCostTotal,
                                  List<CaseBehaviorCheck> checks,
                                  List<CaseBehaviorMetric> metrics,
                                  List<String> failureLabels,
                                  List<String> suspectedAttributions,
                                  List<String> supportedAttributions) {
    }

    /** 漂移消费观测面（D08；整面可空 = 无消费观测如实；consumed 可空 = 未观测不猜） */
    public record CaseDriftConsumption(String mode, boolean summaryCommitted,
                                       boolean consumerInvoked, Boolean consumed,
                                       String policyDigest) {
    }

    /**
     * 案例上下文漂移评测条目（ME-T12a/V164；一案例一 grader 版本一条，多版本并存
     * 如实并列）。summaryDigest 可空 = 无压缩事件或观测读失败 ERROR 行如实；
     * consumption 可空 = 无消费观测面；deferred = 本 grader 不评四项如实留痕；
     * jsonb 解析失败的行如实缺席（不猜）。
     */
    public record CaseDriftEntry(String graderVersion, String summaryDigest,
                                 CaseDriftConsumption consumption,
                                 List<CaseBehaviorCheck> checks,
                                 List<CaseBehaviorMetric> metrics,
                                 List<String> failureLabels,
                                 List<String> deferred) {
    }

    /** 案例级证据汇总行（EV-05 Run 证据查询）：status ∈ OK / NO_REPORT / NO_REFS；
     *  byType = 已解析引用的 evidence_type 分桶；unresolvedRefs 单列不入桶 */
    public record CaseEvidenceSummary(UUID caseExecutionId, String scenarioId, int roundNo,
                                      String verdict, UUID rcaRunId, UUID scoredReportId,
                                      String status, long totalRefs, long resolvedRefs,
                                      long unresolvedRefs, Map<String, Long> byType,
                                      long supportingRefs, long refutingRefs,
                                      long undeterminedRefs) {
    }

    public record RunEvidenceSummaryResponse(UUID runId, long caseCount, long casesWithReport,
                                             long totalRefs, Map<String, Long> byType,
                                             List<CaseEvidenceSummary> cases, Instant asOf) {
    }

    /** 案例日志摘要（EV-05 受限日志比较）：时间窗/服务自冻结证据行本身取；
     *  unparseableEvidence>0 = 形状不符证据显式计数（不吞）；truncated=源侧截断或
     *  读面扫描闸截断 */
    public record CaseLogSummary(UUID caseExecutionId, int roundNo, UUID rcaRunId,
                                 long evidenceCount, long totalLines, long errorLines,
                                 boolean truncated, long unparseableEvidence,
                                 List<String> services, String scopeTimeRange,
                                 Instant windowStart, Instant windowEnd,
                                 List<EvalLogCompare.SignatureCount> topSignatures) {
    }

    /** 同轮两侧 diff（任一侧缺席 → null，如实"不可比"而非空桶冒充） */
    public record RoundLogDiff(int roundNo, CaseLogSummary baseline, CaseLogSummary candidate,
                               EvalLogCompare.Diff diff) {
    }

    /** compareStatus ∈ OK / NO_LOG_EVIDENCE（两侧全程零 logs.query 证据——事实空，
     *  与"未统计"区分：rounds 仍按案例列示） */
    public record EvalLogCompareResponse(String scenarioId, UUID baselineRunId,
                                         UUID candidateRunId, String compareStatus,
                                         List<RoundLogDiff> rounds, Instant asOf) {
    }

    // ------------------------------------------------------------------ runs

    /** state 非法或 cursor 无法解析 → IllegalArgumentException（controller 400 面） */
    public EvalRunListResponse listRuns(String state, String cursor, int limit) {
        validateEnum("state", state, RUN_STATES);
        EvalRunPage page = reader.listRuns(state, parseRunCursor(cursor), limit);
        String nextCursor = null;
        if (page.hasMore() && !page.items().isEmpty()) {
            EvalRunRow last = page.items().get(page.items().size() - 1);
            nextCursor = last.startedAt() + "/" + last.runId();
        }
        // EV-06：页内 run 的用量行一次批量取回（禁 N+1），rollup 后进分面
        List<UUID> runIds = page.items().stream().map(EvalRunRow::runId).toList();
        Map<UUID, List<EvalQueryReader.UsageCallRow>> usageByRun = new LinkedHashMap<>();
        for (EvalQueryReader.UsageCallRow callRow : reader.listUsageCallsForRuns(runIds)) {
            usageByRun.computeIfAbsent(callRow.evalRunId(), k -> new ArrayList<>()).add(callRow);
        }
        // EV-09：页内 run 的场景轮次聚合一次批量取回（禁 N+1），稳定性三件套进分面
        Map<UUID, List<EvalQueryReader.ScenarioRoundStatRow>> statsByRun = new LinkedHashMap<>();
        for (EvalQueryReader.ScenarioRoundStatRow statRow :
                reader.listScenarioRoundStatsForRuns(runIds)) {
            statsByRun.computeIfAbsent(statRow.evalRunId(), k -> new ArrayList<>()).add(statRow);
        }
        // BA-176：页内 run 的模型调用失败账一次批量取回（禁 N+1）——版本指标对比
        // "为什么退步"解读面（欠费/限流窗的低分要能与真实判错区分）
        Map<UUID, List<EvalQueryReader.ModelCallFailureRow>> failuresByRun = new LinkedHashMap<>();
        for (EvalQueryReader.ModelCallFailureRow failureRow :
                reader.listModelCallFailuresForRuns(runIds)) {
            failuresByRun.computeIfAbsent(failureRow.evalRunId(), k -> new ArrayList<>())
                    .add(failureRow);
        }
        // BA-177：页内 run 的六要素落档账一次批量取回（禁 N+1）——six_parts_rate
        // 透出（分母=有报告且落档的案例数，口径见 EvalQueryReader 头注）
        Map<UUID, EvalQueryReader.SixPartsStatRow> sixPartsByRun = new LinkedHashMap<>();
        for (EvalQueryReader.SixPartsStatRow sixPartsRow :
                reader.listSixPartsStatsForRuns(runIds)) {
            sixPartsByRun.put(sixPartsRow.evalRunId(), sixPartsRow);
        }
        // D01：页内 run 的回放案例键按数据集版本去重批量取回（禁 N+1）——冻结
        // launch plan 的每场景 plannedRounds 需要据此裁剪回放形态单轮；无快照
        // 行不取（计划面如实 UNKNOWN，不猜）
        Map<String, Set<String>> replayKeysByDataset = new LinkedHashMap<>();
        for (EvalRunRow row : page.items()) {
            String dv = row.datasetVersion();
            if (row.launchPlanJson() != null && dv != null
                    && !replayKeysByDataset.containsKey(dv)) {
                replayKeysByDataset.put(dv,
                        Set.copyOf(reader.listPlanCaseKeys(dv)));
            }
        }
        List<EvalRunListItem> items = new ArrayList<>(page.items().size());
        for (EvalRunRow row : page.items()) {
            items.add(toListItem(row,
                    usageByRun.getOrDefault(row.runId(), List.of()),
                    statsByRun.getOrDefault(row.runId(), List.of()),
                    failuresByRun.getOrDefault(row.runId(), List.of()),
                    sixPartsRateOf(sixPartsByRun.get(row.runId())),
                    plannedRoundsByScenario(row,
                            replayKeysByDataset.getOrDefault(row.datasetVersion(),
                                    Set.of()))));
        }
        return new EvalRunListResponse(List.copyOf(items), nextCursor, Instant.now());
    }

    public Optional<EvalRunDetailResponse> detail(UUID runId) {
        return reader.findRun(runId).map(row -> {
            // 终态汇总列只在收官回填；RUNNING 期从 eval_case_result 现算"截至目前"真值，
            // 前端不再显示"未统计"（settled=0 时保持 null——一个案例都没结清不冒充 0%）
            Double coverage = row.coverage();
            Double condAcc = row.conditionalAccuracy();
            Double e2e = row.endToEndHitRate();
            Double unresolved = row.unresolvedRate();
            Integer tp = row.tp();
            Integer fp = row.fp();
            Integer fn = row.fn();
            QualityFacet quality = qualityFacet(row);
            if (e2e == null) {
                EvalQueryReader.LiveMetricRow live = reader.liveMetrics(runId);
                if (live.settled() > 0) {
                    coverage = divideOrNull(live.decidable(), live.settled());
                    condAcc = live.decidable() > 0 ? divideOrNull(live.hits(), live.decidable()) : null;
                    e2e = divideOrNull(live.hits(), live.settled());
                    unresolved = divideOrNull(live.unresolved(), live.settled());
                    tp = Math.toIntExact(live.tp());
                    fp = Math.toIntExact(live.fp());
                    fn = Math.toIntExact(live.fn());
                    // 质量五件套同步现算（分母=已结清案例数，与"截至目前"口径一致）
                    quality = new QualityFacet(
                            ratio(live.decidable(), live.settled()),
                            live.decidable() > 0 ? ratio(live.hits(), live.decidable()) : unknownRatio(),
                            ratio(live.hits(), live.settled()),
                            ratio(live.unresolved(), live.settled()),
                            live.decidable() > 0
                                    ? ratio(live.decidable() - live.hits(), live.decidable())
                                    : unknownRatio());
                }
            }
            Double precision = tp == null || fp == null ? null : divideOrNull(tp, tp + fp);
            Double recall = tp == null || fn == null ? null : divideOrNull(tp, tp + fn);
            Double f1 = precision == null || recall == null || precision + recall == 0.0
                    ? null : 2 * precision * recall / (precision + recall);
            return new EvalRunDetailResponse(
                    row.runId(), row.datasetVersion(), row.registryDigest(), row.model(),
                    row.promptVersion(), row.configDigest(), row.state(), row.startedAt(),
                    row.finishedAt(), coverage, condAcc,
                    e2e, unresolved, tp, fp, fn,
                    precision, recall, f1,
                    row.caseCount(), row.displayName(), row.mode(), row.totalScenarios(),
                    quality,
                    stabilityFacet(reader.listScenarioRoundStatsForRuns(List.of(runId)),
                            plannedRoundsByScenario(row, replayCaseKeys(row))),
                    facets(row, usageRollup(runId)), Instant.now(),
                    row.terminalReason(), parseLaunchPlan(row.launchPlanJson()),
                    sixPartsRateOf(oneSixPartsRow(reader.listSixPartsStatsForRuns(List.of(runId)))));
        });
    }

    /** 单 run 六要素聚合行（详情面；与列表同一条批量 SQL 路径，口径一致） */
    private static EvalQueryReader.SixPartsStatRow oneSixPartsRow(
            List<EvalQueryReader.SixPartsStatRow> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 六要素完整率三件套（BA-177）：无落档行 → UNKNOWN（不填 0 冒充）；有行 →
     * complete/total。分母=有报告且落档的案例数——无报告案例不可检不进分母
     * （SingleCaseScorer 落档口径），前端须带此口径说明，防止读成全部案例完成率。
     */
    private static RatioStat sixPartsRateOf(EvalQueryReader.SixPartsStatRow row) {
        return row == null ? unknownRatio() : ratio(row.complete(), row.total());
    }

    /** 单 run rollup（详情面；与列表同一条批量 SQL 路径，口径一致） */
    private RunUsageRollup usageRollup(UUID runId) {
        return RunUsageRollup.of(reader.listUsageCallsForRuns(List.of(runId)));
    }

    // ------------------------------------------------------------------ P4 安全汇总

    /** 面计数（violations 按 face 聚合） */
    public record SafetyFaceCount(String face, long count) {
    }

    /**
     * P4 run 级安全汇总（拦截率出数面）：assessed=有裁决案例数；五态分列计数
     * （ME-T02/V158：passes=verdict PASS 计数——旧二态批值不变，五态下不再等于
     * assessed-rejects，NOT_ASSESSED 不得冒充通过）；rejects=SafetyGate REJECT
     * （不安全尝试被账本拦截面捕获）；redteamDecoyAdopted=红队案例
     * root_cause_hit=true（Agent 采纳了诱饵根因=被注入劫持——红队取反评分）。
     * ME-T02 tally 聚合（D03 三事实 + 覆盖分母）：attempted/blocked/executedViolations/
     * assessedFaces/notAssessedFaces = 有 tally 行的合计；tallyCases=有 tally 行数、
     * tallyMissingCases=无 tally 行数（旧批全缺 → 各合计 null 如实"旧口径"，不填 0）。
     * run 未知 → empty（controller 404 面）。
     */
    public record SafetySummaryResponse(UUID runId, long assessedCases,
                                        long rejects, long passes,
                                        long notAssessed, long notApplicable, long errors,
                                        long redteamCases, long redteamDecoyAdopted,
                                        long redteamSafetyRejects,
                                        Long attempted, Long blocked, Long executedViolations,
                                        Long assessedFaces, Long notAssessedFaces,
                                        long tallyCases, long tallyMissingCases,
                                        List<SafetyFaceCount> faceCounts, Instant asOf) {
    }

    public Optional<SafetySummaryResponse> safetySummary(UUID runId) {
        if (reader.findRun(runId).isEmpty()) {
            return Optional.empty();
        }
        List<EvalQueryReader.CaseSafetyRow> rows = reader.listCaseSafety(runId);
        long rejects = rows.stream().filter(r -> "REJECT".equals(r.verdict())).count();
        long passes = rows.stream().filter(r -> "PASS".equals(r.verdict())).count();
        long notAssessed = rows.stream().filter(r -> "NOT_ASSESSED".equals(r.verdict())).count();
        long notApplicable = rows.stream().filter(r -> "NOT_APPLICABLE".equals(r.verdict())).count();
        long errors = rows.stream().filter(r -> "ERROR".equals(r.verdict())).count();
        long redteamCases = rows.stream().filter(EvalQueryReader.CaseSafetyRow::redteam).count();
        long decoyAdopted = rows.stream()
                .filter(EvalQueryReader.CaseSafetyRow::redteam)
                .filter(r -> Boolean.TRUE.equals(r.rootCauseHit()))
                .count();
        long redteamRejects = rows.stream()
                .filter(EvalQueryReader.CaseSafetyRow::redteam)
                .filter(r -> "REJECT".equals(r.verdict())).count();
        long attempted = 0;
        long blocked = 0;
        long executedViolations = 0;
        long assessedFaces = 0;
        long notAssessedFaces = 0;
        long tallyCases = 0;
        Map<String, Long> faces = new java.util.TreeMap<>();
        for (EvalQueryReader.CaseSafetyRow row : rows) {
            if (row.tallyJson() != null) {
                try {
                    JsonNode tally = mapper.readTree(row.tallyJson());
                    attempted += tally.path("attempted").asLong(0);
                    blocked += tally.path("blocked").asLong(0);
                    executedViolations += tally.path("executedViolations").asLong(0);
                    assessedFaces += tally.path("assessedFaces").asLong(0);
                    notAssessedFaces += tally.path("notAssessedFaces").asLong(0);
                    tallyCases++;
                } catch (Exception e) {
                    // tally 解析失败如实不计（不猜；该行归入 tallyMissing 口径之外不冒充）
                }
            }
            if (row.violationsJson() == null) {
                continue;
            }
            try {
                JsonNode node = mapper.readTree(row.violationsJson());
                if (node.isArray()) {
                    node.forEach(v -> faces.merge(v.path("face").asText("UNKNOWN"), 1L, Long::sum));
                }
            } catch (Exception e) {
                // 违规明细解析失败如实跳过该行计数（不猜）
            }
        }
        List<SafetyFaceCount> faceCounts = faces.entrySet().stream()
                .map(e -> new SafetyFaceCount(e.getKey(), e.getValue()))
                .toList();
        boolean noTally = tallyCases == 0;
        return Optional.of(new SafetySummaryResponse(runId, rows.size(), rejects, passes,
                notAssessed, notApplicable, errors,
                redteamCases, decoyAdopted, redteamRejects,
                noTally ? null : attempted, noTally ? null : blocked,
                noTally ? null : executedViolations,
                noTally ? null : assessedFaces, noTally ? null : notAssessedFaces,
                tallyCases, rows.size() - tallyCases,
                List.copyOf(faceCounts), Instant.now()));
    }

    // ------------------------------------------------------------------ M-d T3 过程面汇总

    /**
     * M-d T3 run 级过程面汇总（AgentGuide 工具与轨迹/证据事实性/成本性能维出数面）：
     * 结构通过率=1-STRUCTURE_REJECTED/settled（约束依从）；重复动作率=1-unique/total
     * （V140 过程计数）；工具错误率=FAILED/total（rca_tool_invocation 账本）；
     * 检查点覆盖率=covered/total（V140 路径维）；结论有据率=GROUNDED/assessed；
     * P50/P95=已结清案例延迟分位。分母为 0 → 对应率 null 如实（不冒充 0%）。
     */
    public record ProcessMetricsResponse(UUID runId, long settled, Double structurePassRate,
                                         long toolCallTotal, long toolCallUnique,
                                         Double repeatedActionRate,
                                         long toolCallFailed, Double toolErrorRate,
                                         Long checkpointsTotal, Long checkpointsCovered,
                                         Double checkpointCoverageRate,
                                         long groundedAssessed, long grounded,
                                         Double conclusionGroundedRate,
                                         Long p50LatencyMs, Long p95LatencyMs, Instant asOf) {
    }

    public Optional<ProcessMetricsResponse> processMetricsSummary(UUID runId) {
        if (reader.findRun(runId).isEmpty()) {
            return Optional.empty();
        }
        EvalQueryReader.ProcessMetricsRow m = reader.processMetrics(runId);
        Double structurePassRate = m.settled() == 0 ? null
                : 1.0 - (double) m.structureRejected() / m.settled();
        Double repeatedActionRate = m.toolCallsTotal() == 0 ? null
                : 1.0 - (double) m.toolCallsUnique() / m.toolCallsTotal();
        Double toolErrorRate = m.toolCallTotal() == 0 ? null
                : (double) m.toolCallFailed() / m.toolCallTotal();
        Double checkpointRate = m.checkpointsTotal() == 0 ? null
                : (double) m.checkpointsCovered() / m.checkpointsTotal();
        Double groundedRate = m.groundedAssessed() == 0 ? null
                : (double) m.grounded() / m.groundedAssessed();
        return Optional.of(new ProcessMetricsResponse(runId, m.settled(), structurePassRate,
                m.toolCallsTotal(), m.toolCallsUnique(), repeatedActionRate,
                m.toolCallFailed(), toolErrorRate,
                m.checkpointsTotal(), m.checkpointsCovered(), checkpointRate,
                m.groundedAssessed(), m.grounded(), groundedRate,
                m.p50LatencyMs(), m.p95LatencyMs(), Instant.now()));
    }

    // ------------------------------------------------------------------ M-d T8 审批链观测

    /**
     * M-d T8 run 级审批链存在性汇总（S26「审批流全走起来」出数面）：五表逐级计数，
     * 零值=该级无活动如实（不冒充链路完整）；内容合理性归 judge 与人工复核。
     * run 未知 → empty（controller 404 面）。
     */
    public record ApprovalChainResponse(UUID runId, long intents, long requests, long decisions,
                                        long grants, long authorizations, Instant asOf) {
    }

    public Optional<ApprovalChainResponse> approvalChainSummary(UUID runId) {
        if (reader.findRun(runId).isEmpty()) {
            return Optional.empty();
        }
        EvalQueryReader.ApprovalChainRow c = reader.approvalChain(runId);
        return Optional.of(new ApprovalChainResponse(runId, c.intents(), c.requests(),
                c.decisions(), c.grants(), c.authorizations(), Instant.now()));
    }

    // ------------------------------------------------------------------ P7 judge 汇总

    /** 逐题"是"计数（rubric 校准面：题粒度通过率——<0.7 的题触发 rubric 修订） */
    public record JudgeQuestionStat(String id, long yes, long assessed) {
    }

    // ------------------------------------------------------------------ M-d T5 六要素汇总

    /**
     * M-d T5 run 级六要素汇总出数面：assessed=有检出行案例数（V152 缺席=无行=未评
     * 如实，不冒充）；rate=complete 占比（无检出行 → null 如实）；high/medium/low=
     * 把握短语档位分布。run 未知 → empty（controller 404 面）。
     */
    public record SixPartsSummaryResponse(UUID runId, long assessed, long complete, Double rate,
                                          long high, long medium, long low, Instant asOf) {
    }

    public Optional<SixPartsSummaryResponse> sixPartsSummary(UUID runId) {
        if (reader.findRun(runId).isEmpty()) {
            return Optional.empty();
        }
        List<EvalQueryReader.SixPartsRow> rows = reader.listSixParts(runId);
        long complete = rows.stream().filter(EvalQueryReader.SixPartsRow::complete).count();
        Double rate = rows.isEmpty() ? null : (double) complete / rows.size();
        return Optional.of(new SixPartsSummaryResponse(runId, rows.size(), complete, rate,
                rows.stream().filter(r -> "HIGH".equals(r.confidenceLevel())).count(),
                rows.stream().filter(r -> "MEDIUM".equals(r.confidenceLevel())).count(),
                rows.stream().filter(r -> "LOW".equals(r.confidenceLevel())).count(),
                Instant.now()));
    }

    // ------------------------------------------------------------------ M-e T11 行为评测汇总

    /**
     * 覆盖双轨聚合（D04）：text* = 旧版文本覆盖（报告子串命中，原义保留）合计；
     * evidence* = 新证据覆盖（实际 evidence/result 语料命中）合计——该轨可空
     * （无检查点/读面缺席未评），evidenceAssessed = evidence 轨有值的行数（分母口径
     * 如实披露：只含被评行，无值行不填 0）；全无可加值 → 对应合计 null 如实。
     */
    public record BehaviorCoverageStat(Long textCovered, Long textTotal,
                                       Long evidenceCovered, Long evidenceTotal,
                                       long evidenceAssessed) {
    }

    /** 单项检查五态计数（statusCounts 键 = BehaviorCheckStatus 五态名，TreeMap 定序） */
    public record BehaviorCheckStat(String name, Map<String, Long> statusCounts) {
    }

    /** 指标分子/分母合计（分母 0 = 口径内无对象如实，不约分不填比率） */
    public record BehaviorMetricStat(String name, long numerator, long denominator) {
    }

    /** 失败标签分布行（计数降序、同计数标签升序，确定性） */
    public record BehaviorLabelCount(String label, long count) {
    }

    /**
     * M-e run 级行为评测汇总（eval_case_behavior/V160 出数面）：assessed = 有落档行的
     * 去重案例数（无行 → 0 如实缺席，沿 six-parts 口径——未评不冒充零问题）；
     * rows = 总行数（同案例多 grader 版本并存时 rows > assessed，计数按行聚合如实，
     * graderVersions 披露参与版本）。checks 按检查名聚合五态计数；metrics 按名合计
     * 分子/分母；failureLabels 为 FAIL 机器码分布。run 未知 → empty（controller 404 面）。
     */
    public record BehaviorSummaryResponse(UUID runId, long assessed, long rows,
                                          List<String> graderVersions,
                                          BehaviorCoverageStat coverage,
                                          List<BehaviorCheckStat> checks,
                                          List<BehaviorMetricStat> metrics,
                                          List<BehaviorLabelCount> failureLabels,
                                          Instant asOf) {
    }

    public Optional<BehaviorSummaryResponse> behaviorSummary(UUID runId) {
        if (reader.findRun(runId).isEmpty()) {
            return Optional.empty();
        }
        List<EvalQueryReader.CaseBehaviorRow> rows = reader.listCaseBehavior(runId);
        long assessed = rows.stream().map(EvalQueryReader.CaseBehaviorRow::caseResultId)
                .distinct().count();
        List<String> graderVersions = rows.stream()
                .map(EvalQueryReader.CaseBehaviorRow::graderVersion)
                .filter(Objects::nonNull).distinct().sorted().toList();
        // 覆盖双轨合计：各轨只加有值行（null=该轨未评，不进分母不填 0）
        Long textCovered = null;
        Long textTotal = null;
        Long evidenceCovered = null;
        Long evidenceTotal = null;
        long evidenceAssessed = 0;
        Map<String, Map<String, Long>> checkCounts = new TreeMap<>();
        Map<String, long[]> metricSums = new TreeMap<>();
        Map<String, Long> labelCounts = new TreeMap<>();
        for (EvalQueryReader.CaseBehaviorRow row : rows) {
            JsonNode coverage = readJson(row.coverageJson());
            if (coverage != null && coverage.isObject()) {
                textCovered = addNullable(textCovered, coverage.get("textCovered"));
                textTotal = addNullable(textTotal, coverage.get("textTotal"));
                JsonNode ec = coverage.get("evidenceCovered");
                JsonNode et = coverage.get("evidenceTotal");
                if (ec != null && ec.isNumber() && et != null && et.isNumber()) {
                    evidenceAssessed++;
                    evidenceCovered = addNullable(evidenceCovered, ec);
                    evidenceTotal = addNullable(evidenceTotal, et);
                }
            }
            JsonNode checks = readJson(row.checksJson());
            if (checks != null && checks.isArray()) {
                for (JsonNode check : checks) {
                    String name = check.path("name").asText("UNKNOWN");
                    String status = check.path("status").asText("UNKNOWN");
                    checkCounts.computeIfAbsent(name, k -> new TreeMap<>())
                            .merge(status, 1L, Long::sum);
                }
            }
            JsonNode metrics = readJson(row.metricsJson());
            if (metrics != null && metrics.isArray()) {
                for (JsonNode metric : metrics) {
                    long[] sums = metricSums.computeIfAbsent(
                            metric.path("name").asText("UNKNOWN"), k -> new long[2]);
                    sums[0] += metric.path("numerator").asLong(0);
                    sums[1] += metric.path("denominator").asLong(0);
                }
            }
            JsonNode labels = readJson(row.failureLabelsJson());
            if (labels != null && labels.isArray()) {
                for (JsonNode label : labels) {
                    labelCounts.merge(label.asText("UNKNOWN"), 1L, Long::sum);
                }
            }
        }
        List<BehaviorCheckStat> checks = checkCounts.entrySet().stream()
                .map(e -> new BehaviorCheckStat(e.getKey(), Map.copyOf(e.getValue())))
                .toList();
        List<BehaviorMetricStat> metrics = metricSums.entrySet().stream()
                .map(e -> new BehaviorMetricStat(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
        List<BehaviorLabelCount> labels = labelCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new BehaviorLabelCount(e.getKey(), e.getValue()))
                .toList();
        return Optional.of(new BehaviorSummaryResponse(runId, assessed, rows.size(),
                graderVersions,
                new BehaviorCoverageStat(textCovered, textTotal, evidenceCovered,
                        evidenceTotal, evidenceAssessed),
                List.copyOf(checks), List.copyOf(metrics), List.copyOf(labels),
                Instant.now()));
    }

    // ------------------------------------------------------------------ ME-T12 死循环评测汇总

    /**
     * ME-T12 run 级死循环评测汇总（eval_case_loop/V162 出数面）：assessed = 有落档
     * 行的去重案例数（无行 → 0 如实缺席，沿 behavior 口径——未评不冒充零问题）；
     * rows = 总行数（同案例多 grader 版本并存时 rows > assessed，计数按行聚合如实，
     * graderVersions 披露参与版本）。stopReasons 为终态分布（null 终态 = 观测读
     * 失败 ERROR 行/无可评轨迹，如实单列 NONE）；checks 按检查名聚合五态计数；
     * metrics 按名合计分子/分母；failureLabels 为 FAIL 机器码分布。run 未知 →
     * empty（controller 404 面）。
     */
    public record LoopSummaryResponse(UUID runId, long assessed, long rows,
                                      List<String> graderVersions,
                                      Map<String, Long> stopReasons,
                                      List<BehaviorCheckStat> checks,
                                      List<BehaviorMetricStat> metrics,
                                      List<BehaviorLabelCount> failureLabels,
                                      Instant asOf) {
    }

    public Optional<LoopSummaryResponse> loopSummary(UUID runId) {
        if (reader.findRun(runId).isEmpty()) {
            return Optional.empty();
        }
        List<EvalQueryReader.CaseLoopRow> rows = reader.listCaseLoop(runId);
        long assessed = rows.stream().map(EvalQueryReader.CaseLoopRow::caseResultId)
                .distinct().count();
        List<String> graderVersions = rows.stream()
                .map(EvalQueryReader.CaseLoopRow::graderVersion)
                .filter(Objects::nonNull).distinct().sorted().toList();
        Map<String, Long> stopReasons = new TreeMap<>();
        Map<String, Map<String, Long>> checkCounts = new TreeMap<>();
        Map<String, long[]> metricSums = new TreeMap<>();
        Map<String, Long> labelCounts = new TreeMap<>();
        for (EvalQueryReader.CaseLoopRow row : rows) {
            // null 终态 = ERROR 行/无可评轨迹，如实单列不丢弃
            stopReasons.merge(row.stopReason() == null ? "NONE" : row.stopReason(),
                    1L, Long::sum);
            JsonNode checks = readJson(row.checksJson());
            if (checks != null && checks.isArray()) {
                for (JsonNode check : checks) {
                    String name = check.path("name").asText("UNKNOWN");
                    String status = check.path("status").asText("UNKNOWN");
                    checkCounts.computeIfAbsent(name, k -> new TreeMap<>())
                            .merge(status, 1L, Long::sum);
                }
            }
            JsonNode metrics = readJson(row.metricsJson());
            if (metrics != null && metrics.isArray()) {
                for (JsonNode metric : metrics) {
                    long[] sums = metricSums.computeIfAbsent(
                            metric.path("name").asText("UNKNOWN"), k -> new long[2]);
                    sums[0] += metric.path("numerator").asLong(0);
                    sums[1] += metric.path("denominator").asLong(0);
                }
            }
            JsonNode labels = readJson(row.failureLabelsJson());
            if (labels != null && labels.isArray()) {
                for (JsonNode label : labels) {
                    labelCounts.merge(label.asText("UNKNOWN"), 1L, Long::sum);
                }
            }
        }
        List<BehaviorCheckStat> checks = checkCounts.entrySet().stream()
                .map(e -> new BehaviorCheckStat(e.getKey(), Map.copyOf(e.getValue())))
                .toList();
        List<BehaviorMetricStat> metrics = metricSums.entrySet().stream()
                .map(e -> new BehaviorMetricStat(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
        List<BehaviorLabelCount> labels = labelCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new BehaviorLabelCount(e.getKey(), e.getValue()))
                .toList();
        return Optional.of(new LoopSummaryResponse(runId, assessed, rows.size(),
                graderVersions, Map.copyOf(stopReasons), List.copyOf(checks),
                List.copyOf(metrics), List.copyOf(labels), Instant.now()));
    }

    // ------------------------------------------------------------------ ME-T12a 协作评测汇总

    /**
     * ME-T12a run 级协作评测汇总（eval_case_collab/V163 出数面）：assessed = 有落档
     * 行的去重案例数（无行 → 0 如实缺席，沿 loop 口径——未评不冒充零问题）；
     * edges/admitted/tokenCostTotal 为各行合计（可空列只加有值行，全无可加值 →
     * null 如实未观测，不填 0）；checks 按检查名聚合五态计数；metrics 按名合计
     * 分子/分母；failureLabels 为 MAST/机制码分布；归因双轨计数分列不混
     * （suspected=疑似无干预对照，supported=重放改善因果归因）。run 未知 →
     * empty（controller 404 面）。
     */
    public record CollabSummaryResponse(UUID runId, long assessed, long rows,
                                        List<String> graderVersions,
                                        Long edges, Long admitted, Long tokenCostTotal,
                                        List<BehaviorCheckStat> checks,
                                        List<BehaviorMetricStat> metrics,
                                        List<BehaviorLabelCount> failureLabels,
                                        long suspectedAttributions,
                                        long supportedAttributions,
                                        Instant asOf) {
    }

    public Optional<CollabSummaryResponse> collabSummary(UUID runId) {
        if (reader.findRun(runId).isEmpty()) {
            return Optional.empty();
        }
        List<EvalQueryReader.CaseCollabRow> rows = reader.listCaseCollab(runId);
        long assessed = rows.stream().map(EvalQueryReader.CaseCollabRow::caseResultId)
                .distinct().count();
        List<String> graderVersions = rows.stream()
                .map(EvalQueryReader.CaseCollabRow::graderVersion)
                .filter(Objects::nonNull).distinct().sorted().toList();
        Long edges = null;
        Long admitted = null;
        Long tokenCost = null;
        Map<String, Map<String, Long>> checkCounts = new TreeMap<>();
        Map<String, long[]> metricSums = new TreeMap<>();
        Map<String, Long> labelCounts = new TreeMap<>();
        long suspected = 0;
        long supported = 0;
        for (EvalQueryReader.CaseCollabRow row : rows) {
            // 可空标量：只加有值行（null=该行未观测，不进合计不填 0）
            if (row.edgeCount() != null) {
                edges = (edges == null ? 0 : edges) + row.edgeCount();
            }
            if (row.admittedCount() != null) {
                admitted = (admitted == null ? 0 : admitted) + row.admittedCount();
            }
            if (row.tokenCostTotal() != null) {
                tokenCost = (tokenCost == null ? 0 : tokenCost) + row.tokenCostTotal();
            }
            JsonNode checks = readJson(row.checksJson());
            if (checks != null && checks.isArray()) {
                for (JsonNode check : checks) {
                    String name = check.path("name").asText("UNKNOWN");
                    String status = check.path("status").asText("UNKNOWN");
                    checkCounts.computeIfAbsent(name, k -> new TreeMap<>())
                            .merge(status, 1L, Long::sum);
                }
            }
            JsonNode metrics = readJson(row.metricsJson());
            if (metrics != null && metrics.isArray()) {
                for (JsonNode metric : metrics) {
                    long[] sums = metricSums.computeIfAbsent(
                            metric.path("name").asText("UNKNOWN"), k -> new long[2]);
                    sums[0] += metric.path("numerator").asLong(0);
                    sums[1] += metric.path("denominator").asLong(0);
                }
            }
            JsonNode labels = readJson(row.failureLabelsJson());
            if (labels != null && labels.isArray()) {
                for (JsonNode label : labels) {
                    labelCounts.merge(label.asText("UNKNOWN"), 1L, Long::sum);
                }
            }
            // 归因双轨：计数分列不混
            JsonNode suspectedNode = readJson(row.suspectedAttributionsJson());
            if (suspectedNode != null && suspectedNode.isArray()) {
                suspected += suspectedNode.size();
            }
            JsonNode supportedNode = readJson(row.supportedAttributionsJson());
            if (supportedNode != null && supportedNode.isArray()) {
                supported += supportedNode.size();
            }
        }
        List<BehaviorCheckStat> checks = checkCounts.entrySet().stream()
                .map(e -> new BehaviorCheckStat(e.getKey(), Map.copyOf(e.getValue())))
                .toList();
        List<BehaviorMetricStat> metrics = metricSums.entrySet().stream()
                .map(e -> new BehaviorMetricStat(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
        List<BehaviorLabelCount> labels = labelCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new BehaviorLabelCount(e.getKey(), e.getValue()))
                .toList();
        return Optional.of(new CollabSummaryResponse(runId, assessed, rows.size(),
                graderVersions, edges, admitted, tokenCost, List.copyOf(checks),
                List.copyOf(metrics), List.copyOf(labels), suspected, supported,
                Instant.now()));
    }

    // ------------------------------------------------------------------ ME-T12a 漂移评测汇总

    /**
     * ME-T12a run 级上下文漂移评测汇总（eval_case_drift/V164 出数面）：assessed =
     * 有落档行的去重案例数（无行 → 0 如实缺席，沿 collab 口径——未评不冒充零
     * 问题）；withSummary = summary_digest 非空行数（无压缩事件行不计入，如实
     * 区分"评了但没压缩"）；consumption 四件计数（观测行数/committed/invoked/
     * consumed=true——无消费观测面不计入任一项，不填 0 冒充）；checks 按检查名
     * 聚合五态计数；metrics 按名合计分子/分母；failureLabels 计数分布；deferred
     * 为各行不评项并集（如实留痕）。run 未知 → empty（controller 404 面）。
     */
    public record DriftSummaryResponse(UUID runId, long assessed, long rows,
                                       List<String> graderVersions, long withSummary,
                                       long consumptionObserved, long summaryCommitted,
                                       long consumerInvoked, long consumed,
                                       List<BehaviorCheckStat> checks,
                                       List<BehaviorMetricStat> metrics,
                                       List<BehaviorLabelCount> failureLabels,
                                       List<String> deferred, Instant asOf) {
    }

    public Optional<DriftSummaryResponse> driftSummary(UUID runId) {
        if (reader.findRun(runId).isEmpty()) {
            return Optional.empty();
        }
        List<EvalQueryReader.CaseDriftRow> rows = reader.listCaseDrift(runId);
        long assessed = rows.stream().map(EvalQueryReader.CaseDriftRow::caseResultId)
                .distinct().count();
        List<String> graderVersions = rows.stream()
                .map(EvalQueryReader.CaseDriftRow::graderVersion)
                .filter(Objects::nonNull).distinct().sorted().toList();
        long withSummary = rows.stream().filter(r -> r.summaryDigest() != null).count();
        long consumptionObserved = 0;
        long summaryCommitted = 0;
        long consumerInvoked = 0;
        long consumed = 0;
        Map<String, Map<String, Long>> checkCounts = new TreeMap<>();
        Map<String, long[]> metricSums = new TreeMap<>();
        Map<String, Long> labelCounts = new TreeMap<>();
        Set<String> deferredSet = new TreeSet<>();
        for (EvalQueryReader.CaseDriftRow row : rows) {
            JsonNode face = readJson(row.consumptionJson());
            if (face != null && face.isObject()) {
                consumptionObserved++;
                if (face.path("summaryCommitted").asBoolean(false)) {
                    summaryCommitted++;
                }
                if (face.path("consumerInvoked").asBoolean(false)) {
                    consumerInvoked++;
                }
                if (face.path("consumed").asBoolean(false)) {
                    consumed++;
                }
            }
            JsonNode checks = readJson(row.checksJson());
            if (checks != null && checks.isArray()) {
                for (JsonNode check : checks) {
                    String name = check.path("name").asText("UNKNOWN");
                    String status = check.path("status").asText("UNKNOWN");
                    checkCounts.computeIfAbsent(name, k -> new TreeMap<>())
                            .merge(status, 1L, Long::sum);
                }
            }
            JsonNode metrics = readJson(row.metricsJson());
            if (metrics != null && metrics.isArray()) {
                for (JsonNode metric : metrics) {
                    long[] sums = metricSums.computeIfAbsent(
                            metric.path("name").asText("UNKNOWN"), k -> new long[2]);
                    sums[0] += metric.path("numerator").asLong(0);
                    sums[1] += metric.path("denominator").asLong(0);
                }
            }
            JsonNode labels = readJson(row.failureLabelsJson());
            if (labels != null && labels.isArray()) {
                for (JsonNode label : labels) {
                    labelCounts.merge(label.asText("UNKNOWN"), 1L, Long::sum);
                }
            }
            JsonNode deferred = readJson(row.deferredJson());
            if (deferred != null && deferred.isArray()) {
                for (JsonNode d : deferred) {
                    deferredSet.add(d.asText("UNKNOWN"));
                }
            }
        }
        List<BehaviorCheckStat> checks = checkCounts.entrySet().stream()
                .map(e -> new BehaviorCheckStat(e.getKey(), Map.copyOf(e.getValue())))
                .toList();
        List<BehaviorMetricStat> metrics = metricSums.entrySet().stream()
                .map(e -> new BehaviorMetricStat(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
        List<BehaviorLabelCount> labels = labelCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new BehaviorLabelCount(e.getKey(), e.getValue()))
                .toList();
        return Optional.of(new DriftSummaryResponse(runId, assessed, rows.size(),
                graderVersions, withSummary, consumptionObserved, summaryCommitted,
                consumerInvoked, consumed, List.copyOf(checks), List.copyOf(metrics),
                List.copyOf(labels), List.copyOf(deferredSet), Instant.now()));
    }

    /** jsonb ::text 防御解析（失败如实 null，不猜——该行对应面缺席） */
    private JsonNode readJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** 可空计数加和：两侧皆空 → null（不进分母不填 0）；非数值节点视同缺席 */
    private static Long addNullable(Long acc, JsonNode node) {
        if (node == null || !node.isNumber()) {
            return acc;
        }
        return (acc == null ? 0 : acc) + node.asLong();
    }

    /**
     * P7 run 级 judge 汇总（第三判定式出数面）：assessed=有裁决行案例数（judge 未
     * 启用 → 0 如实缺席）；perQuestion=逐题"是"计数（rubric v1 三题二元）；errors=
     * 模型调用/解析失败行数（尝试面审计）。run 未知 → empty（controller 404 面）。
     */
    public record JudgeSummaryResponse(UUID runId, String rubricVersion, String model,
                                       long assessed, long passes, long failures, long errors,
                                       List<JudgeQuestionStat> perQuestion, Instant asOf) {
    }

    public Optional<JudgeSummaryResponse> judgeSummary(UUID runId) {
        if (reader.findRun(runId).isEmpty()) {
            return Optional.empty();
        }
        List<EvalQueryReader.CaseJudgeRow> rows = reader.listJudge(runId);
        String rubricVersion = rows.isEmpty() ? null
                : rows.get(0).rubricVersion();
        String model = rows.isEmpty() ? null : rows.get(0).model();
        long passes = rows.stream().filter(r -> "PASS".equals(r.verdict())).count();
        long failures = rows.stream().filter(r -> "FAIL".equals(r.verdict())).count();
        long errors = rows.stream().filter(r -> "ERROR".equals(r.verdict())).count();
        Map<String, long[]> perQuestion = new java.util.TreeMap<>();
        for (EvalQueryReader.CaseJudgeRow row : rows) {
            if (row.answersJson() == null) {
                continue;
            }
            try {
                JsonNode answers = mapper.readTree(row.answersJson());
                if (!answers.isArray()) {
                    continue;
                }
                for (JsonNode a : answers) {
                    String id = a.path("id").asText();
                    if (id.isBlank()) {
                        continue;
                    }
                    long[] stat = perQuestion.computeIfAbsent(id, k -> new long[2]);
                    stat[1]++;
                    if (a.path("yes").asBoolean(false)) {
                        stat[0]++;
                    }
                }
            } catch (Exception e) {
                // answers 解析失败如实跳过该行题粒度计数（不猜）
            }
        }
        List<JudgeQuestionStat> stats = perQuestion.entrySet().stream()
                .map(e -> new JudgeQuestionStat(e.getKey(), e.getValue()[0],
                        e.getValue()[1]))
                .toList();
        return Optional.of(new JudgeSummaryResponse(runId, rubricVersion, model,
                rows.size(), passes, failures, errors, List.copyOf(stats), Instant.now()));
    }

    // ------------------------------------------------------------------ cases

    /** run 不存在 → empty（controller 404 面）；verdict/cursor 非法 → 400 面 */
    public Optional<EvalCaseListResponse> listCases(UUID runId, String verdict,
                                                    String cursor, int limit) {
        validateEnum("verdict", verdict, VERDICTS);
        if (reader.findRun(runId).isEmpty()) {
            return Optional.empty();
        }
        String afterScenario = null;
        Integer afterRound = null;
        if (cursor != null && !cursor.isBlank()) {
            int slash = cursor.lastIndexOf('/');
            if (slash <= 0 || slash == cursor.length() - 1) {
                throw new IllegalArgumentException(
                        "cursor 非法（期形 <scenarioId>/<roundNo>）");
            }
            try {
                afterScenario = cursor.substring(0, slash);
                afterRound = Integer.parseInt(cursor.substring(slash + 1));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "cursor 非法（期形 <scenarioId>/<roundNo>）");
            }
        }
        EvalCasePage page = reader.listCases(runId, verdict, afterScenario, afterRound, limit);
        String nextCursor = null;
        // 每案 token 聚合批量取回（页内案例 id 一次查询，禁 N+1）；无调用行 → null 如实
        List<UUID> caseIds = page.items().stream().map(EvalCaseRow::caseExecutionId).toList();
        Map<UUID, EvalQueryReader.CaseTokenRow> tokensByCase = new LinkedHashMap<>();
        for (EvalQueryReader.CaseTokenRow tokenRow : reader.listCaseTokenTotals(runId, caseIds)) {
            tokensByCase.put(tokenRow.caseExecutionId(), tokenRow);
        }
        List<EvalCaseItem> items = new ArrayList<>(page.items().size());
        for (EvalCaseRow row : page.items()) {
            EvalQueryReader.CaseTokenRow tokens = tokensByCase.get(row.caseExecutionId());
            items.add(new EvalCaseItem(row.caseExecutionId(), row.scenarioId(), row.roundNo(),
                    row.verdict(), row.rootCauseHit(), summarizeRootCause(mapper, row.expectedRootCauseJson()),
                    summarizeRootCause(mapper, row.actualRootCauseJson()), row.latencyMs(),
                    failureSample(row.failureSampleJson()), row.rcaRunId(), row.scoredReportId(),
                    row.causeComponentHit(), row.causeFaultHit(), row.causeReasonHit(),
                    row.checkpointsTotal(), row.checkpointsCovered(), row.checkpointMatchesJson(),
                    row.conclusionGrounded(), row.toolCallsTotal(), row.toolCallsUnique(),
                    row.difficulty(),
                    tokens == null ? null : tokens.promptTokens(),
                    tokens == null ? null : tokens.completionTokens(),
                    tokens == null ? null : tokens.totalTokens()));
        }
        if (page.hasMore() && !page.items().isEmpty()) {
            EvalCaseRow last = page.items().get(page.items().size() - 1);
            nextCursor = last.scenarioId() + "/" + last.roundNo();
        }
        return Optional.of(new EvalCaseListResponse(List.copyOf(items), nextCursor));
    }

    // ------------------------------------------------------------------ A3 阶段事件

    /**
     * run 阶段事件页（A3/§5.3；eval_phase_event 键集游标 (entered_at, id) 升序，
     * 表无 seq 列不照抄 rca_event after_seq）。run 不存在 → empty（controller 404 面）；
     * cursor 无法解析 → IllegalArgumentException（400 面）。游标明文期形
     * {@code <enteredAt-ISO>|<eventId>}。
     */
    public Optional<EvalPhaseEventListResponse> phaseEvents(UUID runId, String cursor,
                                                            int limit) {
        if (reader.findRun(runId).isEmpty()) {
            return Optional.empty();
        }
        EvalPhaseEventPage page =
                reader.listPhaseEvents(runId, parsePhaseEventCursor(cursor), limit);
        List<EvalPhaseEventItem> items = new ArrayList<>(page.items().size());
        for (EvalPhaseEventRow row : page.items()) {
            items.add(new EvalPhaseEventItem(row.id(), row.phase(), row.enteredAt(),
                    row.workerId(), parseJsonNode(row.detail())));
        }
        String nextCursor = null;
        if (page.hasMore() && !page.items().isEmpty()) {
            EvalPhaseEventRow last = page.items().get(page.items().size() - 1);
            nextCursor = last.enteredAt() + "|" + last.id();
        }
        return Optional.of(new EvalPhaseEventListResponse(List.copyOf(items), nextCursor,
                Instant.now()));
    }

    // ------------------------------------------------------------------ datasets（EV-08 增强）

    /** 数据集列表：RLS 可见计数 + 分区真实计数（HOLDOUT 只出计数）+ 当前 rubric 版本 */
    public DatasetListResponse datasets() {
        Map<UUID, Map<String, Long>> counts = partitionCounts();
        List<DatasetItem> items = new ArrayList<>();
        for (DatasetRow row : reader.listDatasets()) {
            items.add(datasetItem(row, counts));
        }
        return new DatasetListResponse(List.copyOf(items), Instant.now());
    }

    /** 数据集详情（name+version 精确键——uq(name,version) 天然唯一）；未知 → empty（404 面） */
    public Optional<DatasetDetailResponse> datasetDetail(String name, String version) {
        Map<UUID, Map<String, Long>> counts = partitionCounts();
        return reader.listDatasets().stream()
                .filter(row -> row.name().equals(name) && row.version().equals(version))
                .findFirst()
                .map(row -> new DatasetDetailResponse(datasetItem(row, counts),
                        rubrics.knownVersions(), Instant.now()));
    }

    /** 分区计数针孔单查询 → datasetVersionId 分桶（不 N+1） */
    private Map<UUID, Map<String, Long>> partitionCounts() {
        Map<UUID, Map<String, Long>> out = new LinkedHashMap<>();
        for (EvalQueryReader.PartitionCountRow row : reader.listPartitionCounts()) {
            out.computeIfAbsent(row.datasetVersionId(), k -> new TreeMap<>())
                    .put(row.partitionClass(), row.caseCount());
        }
        return out;
    }

    /** 数据集案例清单（name+version 精确键——未知数据集版本 → empty 走 404 面；
     *  期望根因三元组非空段 "/" 拼接，全空如实 null） */
    public Optional<DatasetCaseListResponse> datasetCases(String name, String version) {
        boolean exists = reader.listDatasets().stream()
                .anyMatch(row -> row.name().equals(name) && row.version().equals(version));
        if (!exists) {
            return Optional.empty();
        }
        List<DatasetCaseItem> items = new ArrayList<>();
        for (EvalQueryReader.DatasetCaseRow row : reader.listDatasetCases(name, version)) {
            List<String> parts = new ArrayList<>(3);
            for (String part : new String[]{row.expectedComponent(), row.expectedFaultType(),
                    row.expectedReasonCode()}) {
                if (part != null && !part.isBlank()) {
                    parts.add(part);
                }
            }
            items.add(new DatasetCaseItem(row.caseKey(), row.scenarioFamilyId(),
                    row.partitionClass(), row.note(),
                    parts.isEmpty() ? null : String.join("/", parts),
                    parseStringArray(row.expectedSymptomCodesJson())));
        }
        return Optional.of(new DatasetCaseListResponse(name, version,
                List.copyOf(items), Instant.now()));
    }

    private DatasetItem datasetItem(DatasetRow row, Map<UUID, Map<String, Long>> counts) {
        return new DatasetItem(row.name(), row.version(), row.source(), row.sourceClass(),
                row.partitionClass(), row.caseCount(),
                counts.getOrDefault(row.datasetVersionId(), Map.of()), row.families(),
                rubrics.currentVersion(), row.createdAt());
    }

    // ------------------------------------------------------------------ EV-05 案例详情

    /**
     * 案例详情投影（EV-05 §3.4；runId+caseExecutionId 双键定位——按时间猜归属禁用）。
     * 未知 → empty（controller 404 面）。
     */
    public Optional<EvalCaseDetailResponse> caseDetail(UUID runId, UUID caseExecutionId) {
        return reader.findCaseDetail(runId, caseExecutionId).map(row -> {
            CaseEvidenceBlock evidence = evidenceBlock(row);
            return new EvalCaseDetailResponse(
                    row.caseExecutionId(), row.runId(), row.scenarioId(), row.roundNo(),
                    row.selectionPolicyVersion(), row.verdict(), row.rootCauseHit(),
                    summarizeRootCause(mapper, row.expectedRootCauseJson()),
                    summarizeRootCause(mapper, row.actualRootCauseJson()),
                    parseStringArray(row.expectedSymptomCodesJson()),
                    parseStringArray(row.actualSymptomCodesJson()),
                    row.tp(), row.fp(), row.fn(), row.latencyMs(), row.silencePenalty(),
                    failureSample(row.failureSampleJson()), row.createdAt(),
                    scenarioIdentity(row),
                    new LinkageBlock(row.rcaRunId(), row.rcaRunState(), row.incidentId(),
                            row.rcaStartedAt(), row.rcaFinishedAt(), row.scoredAttemptId(),
                            row.scoredReportId(), row.reportSchemaVersion(),
                            row.reportValidationStatus(), row.reportModel(),
                            row.reportCreatedAt()),
                    reportSummary(row), evidence, behaviorEntries(row), loopEntries(row),
                    collabEntries(row), driftEntries(row), Instant.now());
        });
    }

    /** 案例协作评测条目装配（ME-T12a；无落档 → 空表如实缺席；jsonb 解析失败行
     *  跳过不猜；归因双轨分列不混） */
    private List<CaseCollabEntry> collabEntries(EvalCaseDetailRow row) {
        List<EvalQueryReader.CaseCollabRow> rows =
                reader.listCaseCollabForCase(row.caseExecutionId());
        List<CaseCollabEntry> out = new ArrayList<>(rows.size());
        for (EvalQueryReader.CaseCollabRow r : rows) {
            try {
                JsonNode checks = mapper.readTree(r.checksJson());
                JsonNode metrics = mapper.readTree(r.metricsJson());
                JsonNode labels = mapper.readTree(r.failureLabelsJson());
                JsonNode suspected = mapper.readTree(r.suspectedAttributionsJson());
                JsonNode supported = mapper.readTree(r.supportedAttributionsJson());
                List<CaseBehaviorCheck> checkItems = new ArrayList<>();
                if (checks.isArray()) {
                    for (JsonNode c : checks) {
                        List<String> checkRefs = new ArrayList<>();
                        JsonNode crs = c.path("evidenceRefs");
                        if (crs.isArray()) {
                            crs.forEach(x -> checkRefs.add(x.asText()));
                        }
                        checkItems.add(new CaseBehaviorCheck(
                                c.path("name").asText(null),
                                c.path("status").asText(null),
                                c.path("reasonCode").asText(null),
                                List.copyOf(checkRefs)));
                    }
                }
                List<CaseBehaviorMetric> metricItems = new ArrayList<>();
                if (metrics.isArray()) {
                    for (JsonNode m : metrics) {
                        metricItems.add(new CaseBehaviorMetric(
                                m.path("name").asText(null),
                                m.path("numerator").asLong(0),
                                m.path("denominator").asLong(0)));
                    }
                }
                out.add(new CaseCollabEntry(r.graderVersion(), r.edgeCount(),
                        r.admittedCount(), r.tokenCostTotal(),
                        List.copyOf(checkItems), List.copyOf(metricItems),
                        stringList(labels), stringList(suspected), stringList(supported)));
            } catch (Exception e) {
                // jsonb 解析失败该行如实缺席（不猜；与行为评测条目同律）
            }
        }
        return List.copyOf(out);
    }

    /** 案例上下文漂移评测条目装配（ME-T12a/D08；无落档 → 空表如实缺席；jsonb
     *  解析失败行跳过不猜；consumption 无观测面如实 null 透传） */
    private List<CaseDriftEntry> driftEntries(EvalCaseDetailRow row) {
        List<EvalQueryReader.CaseDriftRow> rows =
                reader.listCaseDriftForCase(row.caseExecutionId());
        List<CaseDriftEntry> out = new ArrayList<>(rows.size());
        for (EvalQueryReader.CaseDriftRow r : rows) {
            try {
                JsonNode checks = mapper.readTree(r.checksJson());
                JsonNode metrics = mapper.readTree(r.metricsJson());
                JsonNode labels = mapper.readTree(r.failureLabelsJson());
                JsonNode deferred = mapper.readTree(r.deferredJson());
                List<CaseBehaviorCheck> checkItems = new ArrayList<>();
                if (checks.isArray()) {
                    for (JsonNode c : checks) {
                        List<String> checkRefs = new ArrayList<>();
                        JsonNode crs = c.path("evidenceRefs");
                        if (crs.isArray()) {
                            crs.forEach(x -> checkRefs.add(x.asText()));
                        }
                        checkItems.add(new CaseBehaviorCheck(
                                c.path("name").asText(null),
                                c.path("status").asText(null),
                                c.path("reasonCode").asText(null),
                                List.copyOf(checkRefs)));
                    }
                }
                List<CaseBehaviorMetric> metricItems = new ArrayList<>();
                if (metrics.isArray()) {
                    for (JsonNode m : metrics) {
                        metricItems.add(new CaseBehaviorMetric(
                                m.path("name").asText(null),
                                m.path("numerator").asLong(0),
                                m.path("denominator").asLong(0)));
                    }
                }
                CaseDriftConsumption face = null;
                JsonNode faceNode = r.consumptionJson() == null ? null
                        : mapper.readTree(r.consumptionJson());
                if (faceNode != null && faceNode.isObject()) {
                    face = new CaseDriftConsumption(
                            faceNode.path("mode").asText(null),
                            faceNode.path("summaryCommitted").asBoolean(false),
                            faceNode.path("consumerInvoked").asBoolean(false),
                            faceNode.path("consumed").isBoolean()
                                    ? faceNode.path("consumed").asBoolean() : null,
                            faceNode.path("policyDigest").asText(null));
                }
                out.add(new CaseDriftEntry(r.graderVersion(), r.summaryDigest(), face,
                        List.copyOf(checkItems), List.copyOf(metricItems),
                        stringList(labels), stringList(deferred)));
            } catch (Exception e) {
                // jsonb 解析失败该行如实缺席（不猜；与协作评测条目同律）
            }
        }
        return List.copyOf(out);
    }

    /** jsonb 数组节点 → 字符串表（非数组 → 空表如实） */
    private static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(x -> out.add(x.asText()));
        }
        return List.copyOf(out);
    }

    /** 案例死循环评测条目装配（ME-T12；无落档 → 空表如实缺席；jsonb 解析失败行
     *  跳过不猜） */
    private List<CaseLoopEntry> loopEntries(EvalCaseDetailRow row) {
        List<EvalQueryReader.CaseLoopRow> rows =
                reader.listCaseLoopForCase(row.caseExecutionId());
        List<CaseLoopEntry> out = new ArrayList<>(rows.size());
        for (EvalQueryReader.CaseLoopRow r : rows) {
            try {
                JsonNode checks = mapper.readTree(r.checksJson());
                JsonNode metrics = mapper.readTree(r.metricsJson());
                JsonNode labels = mapper.readTree(r.failureLabelsJson());
                List<CaseBehaviorCheck> checkItems = new ArrayList<>();
                if (checks.isArray()) {
                    for (JsonNode c : checks) {
                        List<String> checkRefs = new ArrayList<>();
                        JsonNode crs = c.path("evidenceRefs");
                        if (crs.isArray()) {
                            crs.forEach(x -> checkRefs.add(x.asText()));
                        }
                        checkItems.add(new CaseBehaviorCheck(
                                c.path("name").asText(null),
                                c.path("status").asText(null),
                                c.path("reasonCode").asText(null),
                                List.copyOf(checkRefs)));
                    }
                }
                List<CaseBehaviorMetric> metricItems = new ArrayList<>();
                if (metrics.isArray()) {
                    for (JsonNode m : metrics) {
                        metricItems.add(new CaseBehaviorMetric(
                                m.path("name").asText(null),
                                m.path("numerator").asLong(0),
                                m.path("denominator").asLong(0)));
                    }
                }
                List<String> labelItems = new ArrayList<>();
                if (labels.isArray()) {
                    labels.forEach(x -> labelItems.add(x.asText()));
                }
                out.add(new CaseLoopEntry(r.graderVersion(), r.stopReason(),
                        r.detectionEventIndex(), r.firstNoProgressEventIndex(),
                        r.postStopNewActions(), r.physicalCallsFromOnset(),
                        r.tokensFromOnset(), r.secondsFromOnset(),
                        List.copyOf(checkItems), List.copyOf(metricItems),
                        List.copyOf(labelItems)));
            } catch (Exception e) {
                // jsonb 解析失败该行如实缺席（不猜；与行为评测条目同律）
            }
        }
        return List.copyOf(out);
    }

    /** 案例行为评测条目装配（ME-T04；无落档 → 空表如实缺席；jsonb 解析失败行跳过不猜） */
    private List<CaseBehaviorEntry> behaviorEntries(EvalCaseDetailRow row) {
        List<EvalQueryReader.CaseBehaviorRow> rows =
                reader.listCaseBehaviorForCase(row.caseExecutionId());
        List<CaseBehaviorEntry> out = new ArrayList<>(rows.size());
        for (EvalQueryReader.CaseBehaviorRow r : rows) {
            try {
                JsonNode coverage = mapper.readTree(r.coverageJson());
                JsonNode checks = mapper.readTree(r.checksJson());
                JsonNode metrics = mapper.readTree(r.metricsJson());
                JsonNode labels = mapper.readTree(r.failureLabelsJson());
                JsonNode refs = mapper.readTree(r.evidenceRefsJson());
                List<CaseBehaviorCheck> checkItems = new ArrayList<>();
                if (checks.isArray()) {
                    for (JsonNode c : checks) {
                        List<String> checkRefs = new ArrayList<>();
                        JsonNode crs = c.path("evidenceRefs");
                        if (crs.isArray()) {
                            crs.forEach(x -> checkRefs.add(x.asText()));
                        }
                        checkItems.add(new CaseBehaviorCheck(
                                c.path("name").asText(null),
                                c.path("status").asText(null),
                                c.path("reasonCode").asText(null),
                                List.copyOf(checkRefs)));
                    }
                }
                List<CaseBehaviorMetric> metricItems = new ArrayList<>();
                if (metrics.isArray()) {
                    for (JsonNode m : metrics) {
                        metricItems.add(new CaseBehaviorMetric(
                                m.path("name").asText(null),
                                m.path("numerator").asLong(0),
                                m.path("denominator").asLong(0)));
                    }
                }
                List<String> labelItems = new ArrayList<>();
                if (labels.isArray()) {
                    labels.forEach(x -> labelItems.add(x.asText()));
                }
                List<String> refItems = new ArrayList<>();
                if (refs.isArray()) {
                    refs.forEach(x -> refItems.add(x.asText()));
                }
                out.add(new CaseBehaviorEntry(r.graderVersion(), r.traceDigest(),
                        new CaseBehaviorCoverage(
                                intOrNull(coverage.get("textCovered")),
                                intOrNull(coverage.get("textTotal")),
                                intOrNull(coverage.get("evidenceCovered")),
                                intOrNull(coverage.get("evidenceTotal"))),
                        List.copyOf(checkItems), List.copyOf(metricItems),
                        List.copyOf(labelItems), List.copyOf(refItems)));
            } catch (Exception e) {
                // jsonb 解析失败该行如实缺席（不猜；与安全违规明细同律）
            }
        }
        return List.copyOf(out);
    }

    /** jsonb 数值节点 → Integer（null/非数值 → null 如实，不填 0） */
    private static Integer intOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.intValue() : null;
    }

    /**
     * 场景身份：case_version 精确键解析走匹配列表（BA-169）——单命中 resolved；
     * ≥2 命中 = 归属歧义（unresolvedReason=AMBIGUOUS + matchedDatasets，按纪律不猜其一）；
     * 0 命中 = 无匹配或 HOLDOUT 不可见（NO_MATCH_OR_HOLDOUT，安全纪律不区分两者）。
     */
    private ScenarioIdentity scenarioIdentity(EvalCaseDetailRow row) {
        List<CaseIdentityRow> matches =
                reader.findCaseIdentityMatches(row.datasetVersion(), row.scenarioId());
        if (matches.size() == 1) {
            CaseIdentityRow id = matches.get(0);
            return new ScenarioIdentity(row.scenarioId(), row.datasetVersion(), true,
                    id.caseKey(), id.scenarioFamilyId(), id.contentDigest(), id.partitionClass(),
                    id.datasetName(), id.sourceClass(), id.validFrom(), id.validTo(),
                    null, List.of());
        }
        if (matches.size() >= 2) {
            List<String> datasets = matches.stream()
                    .map(CaseIdentityRow::datasetName).distinct().toList();
            return new ScenarioIdentity(row.scenarioId(), row.datasetVersion(), false,
                    null, null, null, null, null, null, null, null,
                    "AMBIGUOUS", datasets);
        }
        return new ScenarioIdentity(row.scenarioId(), row.datasetVersion(), false,
                null, null, null, null, null, null, null, null,
                "NO_MATCH_OR_HOLDOUT", List.of());
    }

    /** 报告人读摘要（v2 包形状校验失败/非 v2 → null 如实；claims 面在 evidenceBlock） */
    private ReportSummary reportSummary(EvalCaseDetailRow row) {
        EvidencePackageV2 pkg = parsePackage(row);
        return pkg == null ? null
                : new ReportSummary(pkg.summary(), pkg.impact(), pkg.remediation(),
                        pkg.referenceArtifactRefs());
    }

    /** 证据块：支持/反对/未决三列表（claim status 三态直分），引用解析限定本案例 rca_run */
    private CaseEvidenceBlock evidenceBlock(EvalCaseDetailRow row) {
        if (row.scoredReportId() == null || row.packageJson() == null) {
            return new CaseEvidenceBlock("NO_REPORT", List.of(), List.of(), List.of());
        }
        if (row.reportSchemaVersion() == null
                || row.reportSchemaVersion() != EvidencePackageV2.SCHEMA_VERSION) {
            return new CaseEvidenceBlock("UNSUPPORTED_SCHEMA_VERSION",
                    List.of(), List.of(), List.of());
        }
        EvidencePackageV2 pkg = parsePackage(row);
        if (pkg == null) {
            return new CaseEvidenceBlock("PACKAGE_UNPARSEABLE", List.of(), List.of(), List.of());
        }
        Map<UUID, EvidenceMetaRow> resolved = resolveRefs(row.rcaRunId(), pkg.claims());
        List<ClaimEvidenceItem> supporting = new ArrayList<>();
        List<ClaimEvidenceItem> refuting = new ArrayList<>();
        List<ClaimEvidenceItem> undetermined = new ArrayList<>();
        for (ReportClaim claim : pkg.claims()) {
            ClaimEvidenceItem item = claimItem(claim, resolved);
            switch (claim.status()) {
                case TRUE -> supporting.add(item);
                case FALSE -> refuting.add(item);
                case UNKNOWN -> undetermined.add(item);
            }
        }
        return new CaseEvidenceBlock("OK", List.copyOf(supporting),
                List.copyOf(refuting), List.copyOf(undetermined));
    }

    /** v2 包解析（形状校验复用 EvidencePackageV2.fromJson）；失败如实 null */
    private EvidencePackageV2 parsePackage(EvalCaseDetailRow row) {
        if (row.packageJson() == null) {
            return null;
        }
        try {
            return EvidencePackageV2.fromMap(com.objwww.pr.control.alert.application.EvidencePackageJsonCodec.toMap(mapper.readTree(row.packageJson())));
        } catch (Exception e) {
            return null;
        }
    }

    /** claims 全部 evidence_refs 的批量解析（UUID 形态 + 本 rca_run 范围双门槛） */
    private Map<UUID, EvidenceMetaRow> resolveRefs(UUID rcaRunId, List<ReportClaim> claims) {
        if (rcaRunId == null) {
            return Map.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (ReportClaim claim : claims) {
            for (String ref : claim.evidenceRefs()) {
                UUID id = parseUuid(ref);
                if (id != null && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        Map<UUID, EvidenceMetaRow> byId = new LinkedHashMap<>();
        for (EvidenceMetaRow meta : reader.listEvidenceMeta(rcaRunId, ids)) {
            byId.put(meta.evidenceId(), meta);
        }
        return byId;
    }

    private static ClaimEvidenceItem claimItem(ReportClaim claim,
                                               Map<UUID, EvidenceMetaRow> resolved) {
        List<ResolvedEvidenceRef> refs = new ArrayList<>(claim.evidenceRefs().size());
        for (String ref : claim.evidenceRefs()) {
            UUID id = parseUuid(ref);
            EvidenceMetaRow meta = id == null ? null : resolved.get(id);
            refs.add(meta == null
                    ? new ResolvedEvidenceRef(ref, false, null, null, null, null, null, null)
                    : new ResolvedEvidenceRef(ref, true, meta.evidenceId(), meta.evidenceType(),
                            meta.source(), meta.timeStart(), meta.timeEnd(),
                            meta.payloadDigest()));
        }
        return new ClaimEvidenceItem(claim.claimType(), claim.component(), claim.faultType(),
                List.copyOf(refs));
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ EV-05 Run 证据汇总

    /**
     * Run 级证据汇总（EV-05；按案例分组）。无报告案例如实 NO_REPORT（不计 0 冒充有统计）；
     * 有报告零引用 = NO_REFS（真实 0）。run 未知 → empty（404 面）。
     */
    public Optional<RunEvidenceSummaryResponse> evidenceSummary(UUID runId) {
        if (reader.findRun(runId).isEmpty()) {
            return Optional.empty();
        }
        List<CaseEvidenceRefRow> rows = reader.listCaseEvidenceRefs(runId);
        Map<UUID, List<CaseEvidenceRefRow>> byCase = new LinkedHashMap<>();
        for (CaseEvidenceRefRow row : rows) {
            byCase.computeIfAbsent(row.caseExecutionId(), k -> new ArrayList<>()).add(row);
        }
        List<CaseEvidenceSummary> cases = new ArrayList<>(byCase.size());
        Map<String, Long> runByType = new TreeMap<>();
        long totalRefs = 0;
        long casesWithReport = 0;
        for (List<CaseEvidenceRefRow> caseRows : byCase.values()) {
            CaseEvidenceSummary summary = caseEvidenceSummary(caseRows);
            cases.add(summary);
            totalRefs += summary.totalRefs();
            if (summary.scoredReportId() != null) {
                casesWithReport++;
            }
            summary.byType().forEach((type, n) -> runByType.merge(type, n, Long::sum));
        }
        return Optional.of(new RunEvidenceSummaryResponse(runId, cases.size(),
                casesWithReport, totalRefs, Map.copyOf(runByType), List.copyOf(cases),
                Instant.now()));
    }

    /** 单案例汇总：扁平行（含哨兵行）→ 计数 + 类型分桶 + 支持/反对/未决引用计数 */
    private static CaseEvidenceSummary caseEvidenceSummary(List<CaseEvidenceRefRow> caseRows) {
        CaseEvidenceRefRow first = caseRows.get(0);
        Map<String, Long> byType = new TreeMap<>();
        long total = 0, resolved = 0, unresolved = 0;
        long supporting = 0, refuting = 0, undetermined = 0;
        for (CaseEvidenceRefRow row : caseRows) {
            if (row.ref() == null) {
                continue; // 哨兵行（无报告/无引用）
            }
            total++;
            if (row.evidenceId() != null) {
                resolved++;
                byType.merge(row.evidenceType(), 1L, Long::sum);
            } else {
                unresolved++;
            }
            if (ClaimStatus.TRUE.name().equals(row.claimStatus())) {
                supporting++;
            } else if (ClaimStatus.FALSE.name().equals(row.claimStatus())) {
                refuting++;
            } else {
                undetermined++;
            }
        }
        String status = first.scoredReportId() == null ? "NO_REPORT"
                : total == 0 ? "NO_REFS" : "OK";
        return new CaseEvidenceSummary(first.caseExecutionId(), first.scenarioId(),
                first.roundNo(), first.verdict(), first.rcaRunId(), first.scoredReportId(),
                status, total, resolved, unresolved, Map.copyOf(byType),
                supporting, refuting, undetermined);
    }

    // ------------------------------------------------------------------ EV-05 受限日志比较

    static final int MAX_SCENARIO_ID_CHARS = 512;

    /**
     * 受限日志比较（EV-05 §3.4/§5.3 末行）：基线 vs 候选 run 同场景案例的
     * logs.query 冻结证据错误签名差异。受限面：只收 baselineRunId/candidateRunId/
     * scenarioId 三参数（时间窗/服务自案例关联证据行取，不接受自由查询参数，
     * 不发新 Loki 查询）。任一 run 未知 → empty（404 面）；参数非法 → 400 面。
     */
    public Optional<EvalLogCompareResponse> logCompare(UUID baselineRunId, UUID candidateRunId,
                                                       String scenarioId) {
        if (scenarioId == null || scenarioId.isBlank()
                || scenarioId.length() > MAX_SCENARIO_ID_CHARS) {
            throw new IllegalArgumentException("scenarioId 必填且不超 "
                    + MAX_SCENARIO_ID_CHARS + " 字符");
        }
        if (baselineRunId.equals(candidateRunId)) {
            throw new IllegalArgumentException("baselineRunId 与 candidateRunId 不得相同");
        }
        if (reader.findRun(baselineRunId).isEmpty() || reader.findRun(candidateRunId).isEmpty()) {
            return Optional.empty();
        }
        SideLogs baseline = logSummaries(reader.listCaseLogEvidence(baselineRunId, scenarioId));
        SideLogs candidate = logSummaries(reader.listCaseLogEvidence(candidateRunId, scenarioId));
        Set<Integer> rounds = new TreeSet<>(baseline.byRound().keySet());
        rounds.addAll(candidate.byRound().keySet());
        List<RoundLogDiff> roundDiffs = new ArrayList<>(rounds.size());
        for (int round : rounds) {
            CaseLogSummary b = baseline.byRound().get(round);
            CaseLogSummary c = candidate.byRound().get(round);
            EvalLogCompare.Diff diff = b == null || c == null ? null
                    : EvalLogCompare.diff(baseline.bucketsByRound().getOrDefault(round, Map.of()),
                            candidate.bucketsByRound().getOrDefault(round, Map.of()),
                            EvalLogCompare.TOP_SIGNATURES);
            roundDiffs.add(new RoundLogDiff(round, b, c, diff));
        }
        // 两侧全程零证据 = 事实空（NO_LOG_EVIDENCE），与"未统计"区分
        String status = baseline.byRound().isEmpty() && candidate.byRound().isEmpty()
                ? "NO_LOG_EVIDENCE" : "OK";
        return Optional.of(new EvalLogCompareResponse(scenarioId, baselineRunId,
                candidateRunId, status, List.copyOf(roundDiffs), Instant.now()));
    }

    // ------------------------------------------------------------------ R6/EV-06 usage 投影

    /**
     * usageStatus 三态（R4/EU19 契约——绝不把未定价并入 0）：priced=已确认价目落账；
     * unpriced=有 usage 无价目（pricing_version='unpriced'，待运维核定，费用待对账）；
     * usage_missing=供应商未回报 usage / FAILED / UNKNOWN（用量与费用双未知）。
     */
    static String usageStatusOf(EvalQueryReader.UsageCallRow row) {
        if (row.usageMissing() || !"SUCCESS".equals(row.state())
                || row.promptTokens() == null) {
            return "usage_missing";
        }
        return "unpriced".equals(row.pricingVersion()) ? "unpriced" : "priced";
    }

    /** 分组投影（字段名即 JSON 契约）；usage_missing 组 tokens/cost 恒 null（不猜零），
     * unpriced 组 cost 恒 null（待价目）；多币种/多价版分属不同组，绝不跨组相加（EU20）。 */
    public record UsageGroup(String roleId, String state, String usageStatus,
                             String currency, String pricingVersion, long calls,
                             Long promptTokens, Long completionTokens, Long totalTokens,
                             Long costMicros) {
    }

    public record UsageResponse(UUID runId, long totalCalls, long usageMissingCalls,
                                List<UsageGroup> groups, Instant asOf) {
    }

    /**
     * Run 用量投影（EV-06）：eval run 关联（rca_run_id 身份链，RV08 不碰 PR 域账本）
     * 的已结算模型调用按 (roleId, state, usageStatus, currency, pricingVersion) 分组。
     * 未知 run → empty（404 面）。
     */
    public Optional<UsageResponse> usage(UUID runId) {
        if (reader.findRun(runId).isEmpty()) {
            return Optional.empty();
        }
        List<EvalQueryReader.UsageCallRow> rows = reader.listUsageCalls(runId);
        record Key(String roleId, String state, String usageStatus,
                   String currency, String pricingVersion) {
        }
        Map<Key, UsageGroupBuilder> byKey = new LinkedHashMap<>();
        long usageMissingCalls = 0;
        for (EvalQueryReader.UsageCallRow row : rows) {
            String status = usageStatusOf(row);
            if ("usage_missing".equals(status)) {
                usageMissingCalls++;
            }
            byKey.computeIfAbsent(new Key(row.roleId(), row.state(), status,
                            "priced".equals(status) ? row.currency() : null,
                            "usage_missing".equals(status) ? null : row.pricingVersion()),
                    k -> new UsageGroupBuilder(k.roleId(), k.state(), k.usageStatus(),
                            k.currency(), k.pricingVersion())).add(row);
        }
        List<UsageGroup> groups = byKey.values().stream()
                .map(UsageGroupBuilder::build)
                .sorted(java.util.Comparator.comparing(UsageGroup::roleId)
                        .thenComparing(UsageGroup::state)
                        .thenComparing(UsageGroup::usageStatus))
                .toList();
        return Optional.of(new UsageResponse(runId, rows.size(), usageMissingCalls,
                groups, Instant.now()));
    }

    /** 分组累加器（usage_missing 组不累 tokens；unpriced 组不累 cost——不猜零） */
    private static final class UsageGroupBuilder {
        private final String roleId;
        private final String state;
        private final String usageStatus;
        private final String currency;
        private final String pricingVersion;
        private long calls;
        private Long promptTokens;
        private Long completionTokens;
        private Long totalTokens;
        private Long costMicros;

        UsageGroupBuilder(String roleId, String state, String usageStatus,
                String currency, String pricingVersion) {
            this.roleId = roleId;
            this.state = state;
            this.usageStatus = usageStatus;
            this.currency = currency;
            this.pricingVersion = pricingVersion;
        }

        void add(EvalQueryReader.UsageCallRow row) {
            calls++;
            if ("usage_missing".equals(usageStatus)) {
                return;
            }
            promptTokens = nvl(promptTokens) + row.promptTokens();
            completionTokens = nvl(completionTokens) + row.completionTokens();
            totalTokens = nvl(totalTokens) + row.totalTokens();
            if ("priced".equals(usageStatus)) {
                costMicros = nvl(costMicros) + row.costMicros();
            }
        }

        private static long nvl(Long v) {
            return v == null ? 0L : v;
        }

        UsageGroup build() {
            return new UsageGroup(roleId, state, usageStatus, currency, pricingVersion,
                    calls, promptTokens, completionTokens, totalTokens, costMicros);
        }
    }

    /** 一侧（run+scenario）的装配结果：round → 摘要 + round → 签名桶（diff 输入） */
    private record SideLogs(Map<Integer, CaseLogSummary> byRound,
                            Map<Integer, Map<String, Long>> bucketsByRound) {
    }

    /** 案例日志行 → SideLogs（uq(run,scenario,round) 保证 round 唯一案例） */
    private SideLogs logSummaries(List<CaseLogEvidenceRow> rows) {
        Map<UUID, List<CaseLogEvidenceRow>> byCase = new LinkedHashMap<>();
        for (CaseLogEvidenceRow row : rows) {
            byCase.computeIfAbsent(row.caseExecutionId(), k -> new ArrayList<>()).add(row);
        }
        Map<Integer, CaseLogSummary> byRound = new LinkedHashMap<>();
        Map<Integer, Map<String, Long>> bucketsByRound = new LinkedHashMap<>();
        for (List<CaseLogEvidenceRow> caseRows : byCase.values()) {
            CaseLogEvidenceRow first = caseRows.get(0);
            List<EvalLogCompare.LogLine> lines = new ArrayList<>();
            Set<String> services = new TreeSet<>();
            String scopeTimeRange = null;
            boolean truncated = false;
            long unparseable = 0;
            for (CaseLogEvidenceRow ev : caseRows) {
                EvalLogCompare.Extraction ex = EvalLogCompare.extract(mapper, ev.payloadJson());
                if (!ex.shapeOk()) {
                    unparseable++;
                    continue;
                }
                truncated |= ex.truncated();
                if (ev.scopeJson() != null && scopeTimeRange == null) {
                    scopeTimeRange = scopeTimeRange(ev.scopeJson());
                }
                for (EvalLogCompare.LogLine line : ex.lines()) {
                    if (lines.size() >= EvalLogCompare.MAX_LINES_PER_EVIDENCE) {
                        truncated = true; // 读面扫描闸截断显式标记
                        break;
                    }
                    lines.add(line);
                    if (line.service() != null) {
                        services.add(line.service());
                    }
                }
            }
            Map<String, Long> buckets = EvalLogCompare.bucketBySignature(lines);
            bucketsByRound.put(first.roundNo(), buckets);
            long errorLines = buckets.values().stream().mapToLong(Long::longValue).sum();
            Instant windowStart = null;
            Instant windowEnd = null;
            for (EvalLogCompare.LogLine line : lines) {
                if (line.ts() == null) {
                    continue;
                }
                windowStart = windowStart == null || line.ts().isBefore(windowStart)
                        ? line.ts() : windowStart;
                windowEnd = windowEnd == null || line.ts().isAfter(windowEnd)
                        ? line.ts() : windowEnd;
            }
            byRound.put(first.roundNo(), new CaseLogSummary(first.caseExecutionId(),
                    first.roundNo(), first.rcaRunId(), caseRows.size(), lines.size(),
                    errorLines, truncated, unparseable, List.copyOf(services),
                    scopeTimeRange, windowStart, windowEnd,
                    EvalLogCompare.top(buckets, EvalLogCompare.TOP_SIGNATURES)));
        }
        return new SideLogs(Map.copyOf(byRound), Map.copyOf(bucketsByRound));
    }

    /** scope json {"time_range": ...} 原文抽取（解析失败如实 null） */
    private String scopeTimeRange(String scopeJson) {
        try {
            JsonNode node = mapper.readTree(scopeJson);
            return node.path("time_range").isTextual() ? node.path("time_range").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 分面装配（纯函数段）

    private EvalRunListItem toListItem(EvalRunRow row,
            List<EvalQueryReader.UsageCallRow> usageRows,
            List<EvalQueryReader.ScenarioRoundStatRow> statRows,
            List<EvalQueryReader.ModelCallFailureRow> failureRows,
            RatioStat sixPartsRate,
            Map<String, Integer> plannedRounds) {
        // BA-176：失败账聚合——总数 + 主因码（计数最高；并列取码序小者，确定性）
        long failedTotal = 0;
        String dominantCode = null;
        long dominantCount = -1;
        for (EvalQueryReader.ModelCallFailureRow f : failureRows) {
            failedTotal += f.count();
            if (f.count() > dominantCount
                    || (f.count() == dominantCount && dominantCode != null
                            && f.errorCode().compareTo(dominantCode) < 0)) {
                dominantCount = f.count();
                dominantCode = f.errorCode();
            }
        }
        return new EvalRunListItem(row.runId(), row.datasetVersion(), row.registryDigest(),
                row.model(), row.promptVersion(), row.configDigest(), row.state(),
                row.startedAt(), row.finishedAt(), row.coverage(), row.conditionalAccuracy(),
                row.endToEndHitRate(), row.unresolvedRate(), row.tp(), row.fp(), row.fn(),
                precision(row), recall(row), f1(row),
                row.displayName(), row.mode(), row.caseCount(), row.totalScenarios(),
                qualityFacet(row), stabilityFacet(statRows, plannedRounds),
                facets(row, RunUsageRollup.of(usageRows)),
                failedTotal, failedTotal > 0 ? dominantCode : null, sixPartsRate,
                row.terminalReason());
    }

    // ------------------------------------------------------------------ F1 派生（读面现算，零迁移）

    /** 症状级 precision = tp/(tp+fp)；计数未回填或分母 0 → null（诚实，不硬塞 0） */
    private static Double precision(EvalRunRow row) {
        if (row.tp() == null || row.fp() == null) {
            return null;
        }
        return divideOrNull(row.tp(), row.tp() + row.fp());
    }

    /** 症状级 recall = tp/(tp+fn)；同 precision 空值纪律 */
    private static Double recall(EvalRunRow row) {
        if (row.tp() == null || row.fn() == null) {
            return null;
        }
        return divideOrNull(row.tp(), row.tp() + row.fn());
    }

    /** f1 = 2PR/(P+R)；任一分量 null 或 P+R=0（tp=0 导致 P=R=0）→ null */
    private static Double f1(EvalRunRow row) {
        Double p = precision(row);
        Double r = recall(row);
        if (p == null || r == null || p + r == 0.0) {
            return null;
        }
        return 2 * p * r / (p + r);
    }

    private static Double divideOrNull(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    /** EV-09：场景轮次聚合行 → 稳定性分面（无案例落档 → 全 UNKNOWN；micro 分母用
     *  真实落档轮次，macro 各场景等权平均；单轮场景天然一致——分子分母如实呈现，
     *  不隐藏轮数）。
     *  D01：plannedRounds = 冻结 launch plan 的每场景计划轮次（null = 计划快照不可用
     *  /缺 caseKeys 身份——不猜计划，进度与完整性结论如实 null/UNKNOWN）。计划可知时
     *  对照实际终态落档轮次：任一计划场景缺轮/缺席 → planComplete=false（暂态观测，
     *  不构成可靠性通过结论）；全部计划 trial 终态落档 → true。 */
    private StabilityFacet stabilityFacet(List<EvalQueryReader.ScenarioRoundStatRow> rows,
                                          Map<String, Integer> plannedRounds) {
        if (rows.isEmpty()) {
            return new StabilityFacet(unknownRatio(), unknownRatio(), unknownRatio(),
                    unknownRatio(), unknownMacro(), unknownRatio(), null, null);
        }
        long rounds = 0;
        long hits = 0;
        long scenarios = 0;
        long allHit = 0;
        long atLeastOnce = 0;
        long consistent = 0;
        double macroSum = 0.0;
        Set<String> observed = new TreeSet<>();
        for (EvalQueryReader.ScenarioRoundStatRow row : rows) {
            rounds += row.rounds();
            hits += row.hits();
            scenarios++;
            observed.add(row.scenarioId());
            macroSum += (double) row.hits() / row.rounds();
            if (row.hits() == row.rounds()) {
                allHit++;
            }
            if (row.hits() > 0) {
                atLeastOnce++;
            }
            if (row.distinctVerdicts() <= 1 && row.distinctActualCauses() <= 1) {
                consistent++;
            }
        }
        RatioStat progress = unknownRatio();
        Integer uniformK = null;
        Boolean complete = null;
        if (plannedRounds != null) {
            long plannedTotal = 0;
            boolean allComplete = true;
            boolean kUniform = true;
            Integer k = null;
            for (Map.Entry<String, Integer> planned : plannedRounds.entrySet()) {
                plannedTotal += planned.getValue();
                if (!observed.contains(planned.getKey())) {
                    allComplete = false;
                }
                if (k == null) {
                    k = planned.getValue();
                } else if (!k.equals(planned.getValue())) {
                    kUniform = false;
                }
            }
            for (EvalQueryReader.ScenarioRoundStatRow row : rows) {
                Integer planned = plannedRounds.get(row.scenarioId());
                // 计划外场景出现 / 落档轮次少于计划 → 完整性结论不可得或不成立
                if (planned == null || row.rounds() < planned) {
                    allComplete = false;
                }
            }
            progress = ratio(rounds, plannedTotal);
            uniformK = kUniform && k != null ? k : null;
            complete = allComplete;
        }
        return new StabilityFacet(ratio(hits, rounds), ratio(allHit, scenarios),
                ratio(consistent, scenarios), ratio(atLeastOnce, scenarios),
                new MacroStat(macroSum / scenarios, scenarios, STATUS_OK),
                progress, uniformK, complete);
    }

    /** 单 run 回放案例键（详情面；无快照/无数据集版本 → 空集，不查库不猜） */
    private Set<String> replayCaseKeys(EvalRunRow row) {
        if (row.launchPlanJson() == null || row.datasetVersion() == null) {
            return Set.of();
        }
        return Set.copyOf(reader.listPlanCaseKeys(row.datasetVersion()));
    }

    /** worker 默认轮次（EvalLaunchPlan 契约：roundsPerScenario 空 = 现有 5×2 编排的 2，
     *  与 EvalCompareService.planRounds 同律不漂移） */
    private static final int DEFAULT_ROUNDS_PER_SCENARIO = 2;

    /**
     * D01：冻结 launch plan → 每场景 plannedRounds。计划键集取快照 caseKeys
     * （FUP-03 注入的 panel 展开后有效场景键集）；回放形态场景（数据集案例键，
     * EvalBatchRunner.effectiveRounds 同律）计划轮次裁剪为 1，其余 =
     * roundsPerScenario（空值 = worker 默认 2）。无快照/解析失败/缺 caseKeys
     * 身份 → null（缺身份不猜计划，调用方不出完整性/通过结论）。
     */
    private Map<String, Integer> plannedRoundsByScenario(EvalRunRow row,
                                                         Set<String> replayCaseKeys) {
        if (row.launchPlanJson() == null) {
            return null;
        }
        try {
            JsonNode plan = mapper.readTree(row.launchPlanJson());
            JsonNode keys = plan.path("caseKeys");
            if (!keys.isArray() || keys.isEmpty()) {
                return null;
            }
            JsonNode roundsNode = plan.path("roundsPerScenario");
            int k = roundsNode.isInt() && roundsNode.asInt() > 0
                    ? roundsNode.asInt() : DEFAULT_ROUNDS_PER_SCENARIO;
            Map<String, Integer> out = new LinkedHashMap<>();
            for (JsonNode key : keys) {
                String scenarioId = key.asText();
                out.put(scenarioId, replayCaseKeys.contains(scenarioId) ? 1 : k);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 质量分面：计数列未回填（RUNNING/FAILED）→ 五比率全 UNKNOWN，不填 0 */
    private static QualityFacet qualityFacet(EvalRunRow row) {
        RatioStat falseConfirmation;
        if (row.decidableCount() == null || row.hitCount() == null) {
            falseConfirmation = unknownRatio();
        } else {
            falseConfirmation = ratio(
                    (long) (row.decidableCount() - row.hitCount()),
                    row.decidableCount().longValue());
        }
        return new QualityFacet(
                ratio(longOf(row.decidableCount()), longOf(row.totalScenarios())),
                ratio(longOf(row.hitCount()), longOf(row.decidableCount())),
                ratio(longOf(row.hitCount()), longOf(row.totalScenarios())),
                ratio(longOf(row.unresolvedCount()), longOf(row.totalScenarios())),
                falseConfirmation);
    }

    /**
     * 状态分面：executionState = 旧 state 同义别名；phase/stageEnteredAt 直透端口投影
     * （无 eval_phase_event 事件 → null）；leaseHeartbeatAt 无租约数据源 → null；
     * usage/cost 自 EV-06 起接真值（rollup 入参：rca_model_call 沿 rca_run_id 身份链
     * 聚合，绝不读 PR 账本冒充——RV08；无链/无已结算调用 → UNKNOWN 如实）。
     * EV-04 真值面：
     * <ul>
     *   <li>recoveryState：V81 列有值直透（PENDING/RECOVERING/VERIFIED/FAILED）；
     *       null + mode=L → UNKNOWN（worker 未上报）；null + 其他模式/旧 CLI 行 →
     *       NOT_APPLICABLE（无现场恢复义务）——不编造；</li>
     *   <li>cancelRequestedAt = eval_run_command 最早受理 CANCEL 提交时刻（受理 =
     *       "取消中"，终态以 state 为准）；无 → null。</li>
     * </ul>
     * EV-07 起 qualityVerdict 接真值：本 run 作为候选的最新 eval_comparison 落档门
     * 结论 PASS → OK、FAIL → VIOLATED、INCONCLUSIVE/NOT_EVALUABLE/无落档 → UNKNOWN
     * （对比资格结论，非单 run 绝对质量——词表语义见 EV-07 文档）。
     */
    private static RunFacets facets(EvalRunRow row, RunUsageRollup usage) {
        String recoveryFacet;
        if (row.recoveryState() != null) {
            recoveryFacet = row.recoveryState();
        } else {
            recoveryFacet = "L".equals(row.mode()) ? STATUS_UNKNOWN : STATUS_NOT_APPLICABLE;
        }
        return new RunFacets(row.state(), row.phase(), row.phaseEnteredAt(),
                row.lastProgressAt(), null,
                qualityVerdict(row), recoveryFacet,
                usage.usageStatus(), usage.costStatus(),
                FRESHNESS_LIVE, row.cancelRequestedAt(),
                usage.costMicros(), usage.currency());
    }

    /** EV-07 qualityVerdict 真值映射（落档门结论 → 分面词表；无落档/无法判定 → UNKNOWN） */
    private static String qualityVerdict(EvalRunRow row) {
        if (row.comparisonGateOutcome() == null) {
            return STATUS_UNKNOWN;
        }
        return switch (row.comparisonGateOutcome()) {
            case "PASS" -> STATUS_OK;
            case "FAIL" -> STATUS_VIOLATED;
            default -> STATUS_UNKNOWN;
        };
    }

    /** launch_plan jsonb → 结构化快照（配置回看 §3.2）；无快照/解析失败如实 null */
    private JsonNode parseLaunchPlan(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(json);
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static RatioStat ratio(Long numerator, Long denominator) {
        if (numerator == null || denominator == null) {
            return unknownRatio();
        }
        if (denominator == 0L) {
            return new RatioStat(numerator, denominator, STATUS_NOT_APPLICABLE);
        }
        return new RatioStat(numerator, denominator, STATUS_OK);
    }

    private static RatioStat unknownRatio() {
        return new RatioStat(null, null, STATUS_UNKNOWN);
    }

    private static MacroStat unknownMacro() {
        return new MacroStat(null, null, STATUS_UNKNOWN);
    }

    private static Long longOf(Integer value) {
        return value == null ? null : value.longValue();
    }

    // ------------------------------------------------------------------ jsonb 摘要化（纯函数段）

    /**
     * root cause jsonb（{"component","fault_type","reason_code"} 三元组快照）→
     * "component/fault_type/reason_code" 摘要串；null 或解析失败如实 null（不冒充）。
     * static 供 EV-07 对比面复用（同口径不漂移）。
     */
    static String summarizeRootCause(ObjectMapper mapper, String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(json);
            if (!node.isObject()) {
                return null;
            }
            List<String> parts = new ArrayList<>(3);
            for (String key : new String[]{"component", "fault_type", "reason_code"}) {
                JsonNode value = node.get(key);
                if (value != null && value.isTextual()) {
                    parts.add(value.asText());
                }
            }
            return parts.isEmpty() ? null : String.join("/", parts);
        } catch (Exception e) {
            return null;
        }
    }

    /** failure_sample jsonb：文本标量取其字面值，其余形态取 JSON 原文；null 如实 null */
    private String failureSample(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(json);
            return node.isTextual() ? node.asText() : json;
        } catch (Exception e) {
            return json;
        }
    }

    /** 症状码 jsonb 数组 → 字符串表（非文本条目跳过）；null/解析失败如实 null */
    private List<String> parseStringArray(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(json);
            if (!node.isArray()) {
                return null;
            }
            List<String> out = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    out.add(item.asText());
                }
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 内部

    private static void validateEnum(String name, String value, Set<String> allowed) {
        if (value != null && !value.isBlank() && !allowed.contains(value)) {
            throw new IllegalArgumentException(name + " 必为 " + allowed + ": " + value);
        }
    }

    private static KeysetCursor parseRunCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        int slash = cursor.lastIndexOf('/');
        if (slash <= 0 || slash == cursor.length() - 1) {
            throw new IllegalArgumentException("cursor 非法（期形 <startedAt>/<runId>）");
        }
        try {
            return new KeysetCursor(Instant.parse(cursor.substring(0, slash)),
                    UUID.fromString(cursor.substring(slash + 1)));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cursor 非法（期形 <startedAt>/<runId>）");
        }
    }

    /** 阶段事件游标（A3；期形 <enteredAt-ISO>|<eventId>，与键集 (entered_at, id) 对应） */
    private static KeysetCursor parsePhaseEventCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        int bar = cursor.lastIndexOf('|');
        if (bar <= 0 || bar == cursor.length() - 1) {
            throw new IllegalArgumentException("cursor 非法（期形 <enteredAt>|<eventId>）");
        }
        try {
            return new KeysetCursor(Instant.parse(cursor.substring(0, bar)),
                    UUID.fromString(cursor.substring(bar + 1)));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cursor 非法（期形 <enteredAt>|<eventId>）");
        }
    }

    /** jsonb 原文 → 结构节点透传（任意形态保留；null/解析失败如实 null） */
    private JsonNode parseJsonNode(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
