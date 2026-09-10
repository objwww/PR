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
 * UI-5 评测只读查询投影服务（/api/eval/** 面；SQL 归 {@link EvalQueryReader}，
 * 本类只承担游标编解码、state/verdict 参数校验与 root cause/failureSample 的
 * jsonb 摘要字符串化——纯函数段，假端口可测，沿 IncidentQueryService 单测模式）。
 *
 * <p>游标明文拼接（沿 IncidentQueryService 惯例）：runs = {@code <startedAt-ISO>/<runId>}；
 * cases = {@code <scenarioId>/<roundNo>}（scenarioId 可含 "/"，按最后一个 "/" 切分）。
 *
 * <p>诚实纪律：RUNNING/FAILED run 的四率与 tp/fp/fn 未回填 → 端口如实 null 直透；
 * actualRootCause 无报告（结构失败/缺席 verdict）→ null；failureSample 无 → null。
 * 六维分析/评分器/发布门无持久化数据，本服务不开对应端点（前端空态明示）。
 */
public class EvalQueryService {

    private static final Set<String> RUN_STATES = Set.of("RUNNING", "SUCCEEDED", "FAILED");
    private static final Set<String> VERDICTS = Set.of("DECIDABLE", "UNRESOLVED",
            "STRUCTURE_REJECTED", "TIMEOUT_OR_ABSENT");

    private final EvalQueryReader reader;
    private final ObjectMapper mapper;

    public EvalQueryService(EvalQueryReader reader, ObjectMapper mapper) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    // ------------------------------------------------------------------ DTO（record，字段名即 JSON 契约）

    public record EvalRunListResponse(List<EvalRunRow> items, String nextCursor) {
    }

    public record EvalRunDetailResponse(UUID runId, String datasetVersion, String registryDigest,
                                        String model, String promptVersion, String configDigest,
                                        String state, Instant startedAt, Instant finishedAt,
                                        Double coverage, Double conditionalAccuracy,
                                        Double endToEndHitRate, Double unresolvedRate,
                                        Integer tp, Integer fp, Integer fn, long caseCount) {
    }

    public record EvalCaseItem(String scenarioId, int roundNo, String verdict,
                               Boolean rootCauseHit, String expectedRootCause,
                               String actualRootCause, Long latencyMs, String failureSample) {
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
        return new EvalRunListResponse(page.items(), nextCursor);
    }

    public Optional<EvalRunDetailResponse> detail(UUID runId) {
        return reader.findRun(runId).map(row -> new EvalRunDetailResponse(
                row.runId(), row.datasetVersion(), row.registryDigest(), row.model(),
                row.promptVersion(), row.configDigest(), row.state(), row.startedAt(),
                row.finishedAt(), row.coverage(), row.conditionalAccuracy(),
                row.endToEndHitRate(), row.unresolvedRate(), row.tp(), row.fp(), row.fn(),
                reader.countCases(runId)));
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
            items.add(new EvalCaseItem(row.scenarioId(), row.roundNo(), row.verdict(),
                    row.rootCauseHit(), summarizeRootCause(row.expectedRootCauseJson()),
                    summarizeRootCause(row.actualRootCauseJson()), row.latencyMs(),
                    failureSample(row.failureSampleJson())));
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
