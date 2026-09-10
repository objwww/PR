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
 *   <li><b>未接线分面不编造</b>：usageStatus/costStatus（RV08 红线：PR 域模型调用账本的
 *       review_run_id 指向 PR review_run，严禁用它汇总 eval/RCA 用量——等 R7 RCA
 *       调用账本显式接线，EV-06）常量 UNKNOWN；qualityVerdict 自 EV-07 起接真值
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
     *  NOT_APPLICABLE=分母 0；UNKNOWN=未回填/未接线 */
    private static final String STATUS_OK = "OK";
    private static final String STATUS_VIOLATED = "VIOLATED";
    private static final String STATUS_NOT_APPLICABLE = "NOT_APPLICABLE";
    private static final String STATUS_UNKNOWN = "UNKNOWN";

    /** 同步直读主表 = 投影无滞后（asOf 即请求时刻） */
    private static final String FRESHNESS_LIVE = "LIVE";

    private final EvalQueryReader reader;
    private final ObjectMapper mapper;

    public EvalQueryService(EvalQueryReader reader, ObjectMapper mapper) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
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
     * 状态分面（§5.1）：各面分开表达，互不顶替。leaseHeartbeatAt 无租约数据源 → null；
     * qualityVerdict 未接线（EV-05+）/usageStatus/costStatus 未接线（EV-06，RV08 红线）
     * → UNKNOWN；EV-04 起 recoveryState/cancelRequestedAt 有真值（见 facets 装配）。
     */
    public record RunFacets(String executionState, String phase, Instant stageEnteredAt,
                            Instant lastProgressAt, Instant leaseHeartbeatAt,
                            String qualityVerdict, String recoveryState,
                            String usageStatus, String costStatus, String freshness,
                            Instant cancelRequestedAt) {
    }

    public record EvalRunListItem(UUID runId, String datasetVersion, String registryDigest,
                                  String model, String promptVersion, String configDigest,
                                  String state, Instant startedAt, Instant finishedAt,
                                  Double coverage, Double conditionalAccuracy,
                                  Double endToEndHitRate, Double unresolvedRate,
                                  Integer tp, Integer fp, Integer fn,
                                  String displayName, String mode,
                                  long caseCount, Integer totalScenarios,
                                  QualityFacet quality, RunFacets facets) {
    }

    public record EvalRunListResponse(List<EvalRunListItem> items, String nextCursor,
                                      Instant asOf) {
    }

    public record EvalRunDetailResponse(UUID runId, String datasetVersion, String registryDigest,
                                        String model, String promptVersion, String configDigest,
                                        String state, Instant startedAt, Instant finishedAt,
                                        Double coverage, Double conditionalAccuracy,
                                        Double endToEndHitRate, Double unresolvedRate,
                                        Integer tp, Integer fp, Integer fn, long caseCount,
                                        String displayName, String mode, Integer totalScenarios,
                                        QualityFacet quality, RunFacets facets, Instant asOf,
                                        String terminalReason, JsonNode launchPlan) {
    }

    public record EvalCaseItem(UUID caseExecutionId, String scenarioId, int roundNo,
                               String verdict, Boolean rootCauseHit, String expectedRootCause,
                               String actualRootCause, Long latencyMs, String failureSample,
                               UUID rcaRunId, UUID scoredReportId) {
    }

    public record EvalCaseListResponse(List<EvalCaseItem> items, String nextCursor) {
    }

    public record DatasetListResponse(List<DatasetRow> items) {
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
     *  身份字段全 null 如实（datasetVersion 恒为 run 直读值，不依赖解析） */
    public record ScenarioIdentity(String scenarioId, String datasetVersion, boolean resolved,
                                   String caseKey, String scenarioFamilyId, String contentDigest,
                                   String partitionClass, String datasetName, String sourceClass,
                                   Instant validFrom, Instant validTo) {
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
                                         CaseEvidenceBlock evidence, Instant asOf) {
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
        List<EvalRunListItem> items = new ArrayList<>(page.items().size());
        for (EvalRunRow row : page.items()) {
            items.add(toListItem(row));
        }
        return new EvalRunListResponse(List.copyOf(items), nextCursor, Instant.now());
    }

    public Optional<EvalRunDetailResponse> detail(UUID runId) {
        return reader.findRun(runId).map(row -> new EvalRunDetailResponse(
                row.runId(), row.datasetVersion(), row.registryDigest(), row.model(),
                row.promptVersion(), row.configDigest(), row.state(), row.startedAt(),
                row.finishedAt(), row.coverage(), row.conditionalAccuracy(),
                row.endToEndHitRate(), row.unresolvedRate(), row.tp(), row.fp(), row.fn(),
                row.caseCount(), row.displayName(), row.mode(), row.totalScenarios(),
                qualityFacet(row), facets(row), Instant.now(),
                row.terminalReason(), parseLaunchPlan(row.launchPlanJson())));
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
        List<EvalCaseItem> items = new ArrayList<>(page.items().size());
        for (EvalCaseRow row : page.items()) {
            items.add(new EvalCaseItem(row.caseExecutionId(), row.scenarioId(), row.roundNo(),
                    row.verdict(), row.rootCauseHit(), summarizeRootCause(mapper, row.expectedRootCauseJson()),
                    summarizeRootCause(mapper, row.actualRootCauseJson()), row.latencyMs(),
                    failureSample(row.failureSampleJson()), row.rcaRunId(), row.scoredReportId()));
        }
        if (page.hasMore() && !page.items().isEmpty()) {
            EvalCaseRow last = page.items().get(page.items().size() - 1);
            nextCursor = last.scenarioId() + "/" + last.roundNo();
        }
        return Optional.of(new EvalCaseListResponse(List.copyOf(items), nextCursor));
    }

    // ------------------------------------------------------------------ datasets

    public DatasetListResponse datasets() {
        return new DatasetListResponse(reader.listDatasets());
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
                    reportSummary(row), evidence, Instant.now());
        });
    }

    /** 场景身份：case_version 精确键解析（无匹配/歧义/HOLDOUT 不可见 → resolved=false） */
    private ScenarioIdentity scenarioIdentity(EvalCaseDetailRow row) {
        Optional<CaseIdentityRow> found =
                reader.findCaseIdentity(row.datasetVersion(), row.scenarioId());
        if (found.isEmpty()) {
            return new ScenarioIdentity(row.scenarioId(), row.datasetVersion(), false,
                    null, null, null, null, null, null, null, null);
        }
        CaseIdentityRow id = found.get();
        return new ScenarioIdentity(row.scenarioId(), row.datasetVersion(), true,
                id.caseKey(), id.scenarioFamilyId(), id.contentDigest(), id.partitionClass(),
                id.datasetName(), id.sourceClass(), id.validFrom(), id.validTo());
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
            return EvidencePackageV2.fromJson(mapper.readTree(row.packageJson()));
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

    private EvalRunListItem toListItem(EvalRunRow row) {
        return new EvalRunListItem(row.runId(), row.datasetVersion(), row.registryDigest(),
                row.model(), row.promptVersion(), row.configDigest(), row.state(),
                row.startedAt(), row.finishedAt(), row.coverage(), row.conditionalAccuracy(),
                row.endToEndHitRate(), row.unresolvedRate(), row.tp(), row.fp(), row.fn(),
                row.displayName(), row.mode(), row.caseCount(), row.totalScenarios(),
                qualityFacet(row), facets(row));
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
     * usage/cost 未接线常量 UNKNOWN（RV08：用量/费用等 R7 RCA 调用账本，
     * 不读 PR 账本冒充）。EV-04 真值面：
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
    private static RunFacets facets(EvalRunRow row) {
        String recoveryFacet;
        if (row.recoveryState() != null) {
            recoveryFacet = row.recoveryState();
        } else {
            recoveryFacet = "L".equals(row.mode()) ? STATUS_UNKNOWN : STATUS_NOT_APPLICABLE;
        }
        return new RunFacets(row.state(), row.phase(), row.phaseEnteredAt(),
                row.lastProgressAt(), null,
                qualityVerdict(row), recoveryFacet, STATUS_UNKNOWN, STATUS_UNKNOWN,
                FRESHNESS_LIVE, row.cancelRequestedAt());
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
}
