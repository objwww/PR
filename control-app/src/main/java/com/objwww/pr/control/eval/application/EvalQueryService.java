package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCasePage;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCaseRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalRunPage;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalRunRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.DatasetRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.KeysetCursor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 *   <li><b>未接线分面不编造</b>：qualityVerdict（无质量门持久化）、recoveryState
 *       （L 模式恢复归 EV-04/DR）、usageStatus/costStatus（RV08 红线：
 *       PR 域模型调用账本的 review_run_id 指向 PR review_run，严禁用它汇总 eval/RCA
 *       用量——等 R7 RCA 调用账本显式接线，EV-06）本期常量 UNKNOWN；</li>
 *   <li><b>asOf</b>：投影同步直读主表，asOf = 请求处理时刻，freshness=LIVE；
 *       列表与详情同口径；</li>
 *   <li><b>案例执行身份（§5.1/RV02）</b>：caseExecutionId = eval_case_result.id
 *       （稳定 uuid），并直读 rcaRunId/scoredReportId 关联链（可空如实 null）。</li>
 * </ul>
 * 六维分析/评分器/发布门无持久化数据，本服务不开对应端点（前端空态明示）。
 */
public class EvalQueryService {

    private static final Set<String> RUN_STATES = Set.of("RUNNING", "SUCCEEDED", "FAILED");
    private static final Set<String> VERDICTS = Set.of("DECIDABLE", "UNRESOLVED",
            "STRUCTURE_REJECTED", "TIMEOUT_OR_ABSENT");

    /** 分面状态词表：OK=有真实数据；NOT_APPLICABLE=分母 0；UNKNOWN=未回填/未接线 */
    private static final String STATUS_OK = "OK";
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
     * qualityVerdict/recoveryState/usageStatus/costStatus 未接线 → UNKNOWN（见类注释）。
     */
    public record RunFacets(String executionState, String phase, Instant stageEnteredAt,
                            Instant lastProgressAt, Instant leaseHeartbeatAt,
                            String qualityVerdict, String recoveryState,
                            String usageStatus, String costStatus, String freshness) {
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
                                        QualityFacet quality, RunFacets facets, Instant asOf) {
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
                qualityFacet(row), facets(row), Instant.now()));
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
                    row.verdict(), row.rootCauseHit(), summarizeRootCause(row.expectedRootCauseJson()),
                    summarizeRootCause(row.actualRootCauseJson()), row.latencyMs(),
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
     * 未接线分面常量 UNKNOWN（RV08：用量/费用等 R7 RCA 调用账本，不读 PR 账本冒充）。
     */
    private static RunFacets facets(EvalRunRow row) {
        return new RunFacets(row.state(), row.phase(), row.phaseEnteredAt(),
                row.lastProgressAt(), null,
                STATUS_UNKNOWN, STATUS_UNKNOWN, STATUS_UNKNOWN, STATUS_UNKNOWN,
                FRESHNESS_LIVE);
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
     */
    String summarizeRootCause(String json) {
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
