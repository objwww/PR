package com.objwww.pr.control.release.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.claim.ClaimLifecycle;
import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.RcaReportRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.infrastructure.observability.AlertMetrics;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.domain.repository.EngineComparisonRepository;
import com.objwww.pr.shared.Digest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * EngineComparisonRecorder（M6-02 观察面成账）：HOLMES 主路径 run 与 Native 侧
 * （M6-02 = AM4 影子 run；M6-05 反向影子复用）的结论<b>归一化</b>落 V32
 * engine_comparison——同形状双侧 outcome + cost 对照 + 六维差异标记。
 *
 * <p>口径冻结（架构 v1.2:736/739 顺延）：
 * <ul>
 *   <li><b>无 GT 只记 disagreement 不判对错</b>：本类零语义裁决——差异以
 *       {@code [{dim, holmes, native}]} 数组留痕，绝不产出"谁正确"；</li>
 *   <li><b>六维差异标记</b> = 架构 :739 实时可比面：{@link #DIM_RESULT}
 *       （根因三元组 + claim 键集）、{@link #DIM_SCHEMA}（双侧包结构验证态）、
 *       {@link #DIM_LATENCY}（run 起止时长）、{@link #DIM_COST}（报告 token）、
 *       {@link #DIM_TOOL_LEGALITY} 与 {@link #DIM_SAFETY_VIOLATION}（M6-02 接线点
 *       无逐 run 工具合法性/安全违规观察面，<b>缺数不标记</b>，M6-05 V33 worker
 *       面补数后启用）；</li>
 *   <li><b>缺数 ≠ 差异</b>：任一侧缺数据的维度不进 flags（影子侧零报告纪律 →
 *       validation/tokens 恒缺，M6-02 期 schema/cost 维天然只在双侧齐时出现）；</li>
 *   <li><b>comparison_key</b> = sha256(canonical(holmesRunId, nativeRunId,
 *       snapshotDigest, candidateDigest))——两侧执行身份 + 同一冻结快照（FUT-06）
 *       + 候选 digest；uq_ec_pair 幂等（重放不重记）。</li>
 * </ul>
 *
 * <p>抽取面容错（观察面不得毒化调查链）：holmes 报告缺失/包解析失败封装进 outcome
 * （{@code report_missing}/{@code parse_error} 标记，claims 记零）而非抛出；仓储
 * append 失败照常传播（DB 面必须可见）。
 */
public class EngineComparisonRecorder {

    /** 差异维度：结果（根因三元组 + claim 键集） */
    public static final String DIM_RESULT = "result";
    /** 差异维度：包结构验证态 */
    public static final String DIM_SCHEMA = "schema";
    /** 差异维度：run 时长 */
    public static final String DIM_LATENCY = "latency";
    /** 差异维度：报告 token 成本 */
    public static final String DIM_COST = "cost";
    /** 差异维度：工具合法性（M6-02 缺观察面，恒不标记；M6-05 启用） */
    public static final String DIM_TOOL_LEGALITY = "tool_legality";
    /** 差异维度：安全违规（M6-02 缺观察面，恒不标记；M6-05 启用） */
    public static final String DIM_SAFETY_VIOLATION = "safety_violation";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Native 无确认根因时的诚实三元组（与 NativeReportAdapter 同语义） */
    private static final String UNKNOWN_COMPONENT = "unknown";
    private static final String UNRESOLVED_FAULT_TYPE = "unresolved";
    private static final String NO_ROOT_CAUSE_REASON = "NO_CONFIRMED_ROOT_CAUSE";

    private final ConfigBundleRepository bundles;
    private final RcaRunRepository runs;
    private final RcaReportRepository reports;
    private final ClaimStore claims;
    private final EngineComparisonRepository store;
    private final AlertMetrics metrics;

    public EngineComparisonRecorder(ConfigBundleRepository bundles,
            RcaRunRepository runs, RcaReportRepository reports, ClaimStore claims,
            EngineComparisonRepository store, AlertMetrics metrics) {
        this.bundles = Objects.requireNonNull(bundles, "bundles");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.reports = Objects.requireNonNull(reports, "reports");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.store = Objects.requireNonNull(store, "store");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** 对照结果：comparison_key 与本次是否新落账（false = uq_ec_pair 幂等重放） */
    public record ComparisonOutcome(UUID nativeRunId, String comparisonKey,
            List<String> flaggedDims, boolean recorded) {
    }

    /**
     * 对照一次 HOLMES 主路径 run 与 Native 侧 run（AM4 影子：同 incident/同
     * generation/同 investigation hash，调用方保证）。
     *
     * @param shadowExecRef 影子执行审计引用（非 blank，如 {@code am4-shadow-trigger}）
     */
    public ComparisonOutcome compareHolmesNative(UUID holmesRunId, UUID nativeRunId,
            String shadowExecRef) {
        if (shadowExecRef == null || shadowExecRef.isBlank()) {
            throw new IllegalArgumentException("shadowExecRef 必填（影子执行审计引用）");
        }
        RcaRun holmes = runs.findById(holmesRunId).orElseThrow(() ->
                new IllegalArgumentException("holmes run 不存在: " + holmesRunId));
        runs.findById(nativeRunId).orElseThrow(() ->
                new IllegalArgumentException("native run 不存在: " + nativeRunId));
        String snapshotDigest = holmes.investigationHash().hex();
        String candidateDigest = bundles.activeDigest().map(Digest::hex).orElse("");

        Map<String, Object> holmesOutcome = holmesOutcome(holmes);
        Map<String, Object> nativeOutcome = nativeOutcome(nativeRunId);
        List<Map<String, Object>> flags = disagreeFlags(holmesOutcome, nativeOutcome);

        String comparisonKey = Digest.sha256Of(String.join("\n",
                holmesRunId.toString(), nativeRunId.toString(),
                snapshotDigest, candidateDigest)).hex();
        boolean recorded = store.append(new EngineComparisonRepository.ComparisonRow(
                nativeRunId, comparisonKey, shadowExecRef, snapshotDigest,
                holmesOutcome, nativeOutcome, flags, null, costCompare(
                holmesOutcome, nativeOutcome)));
        metrics.engineComparison(!flags.isEmpty());
        List<String> dims = flags.stream()
                .map(f -> String.valueOf(f.get("dim")))
                .distinct()
                .toList();
        return new ComparisonOutcome(nativeRunId, comparisonKey, dims, recorded);
    }

    /**
     * M6-05 反向影子对照：Holmes 影子 run（零报告纪律 → 结论由 Worker 从执行
     * artifact 预铸，{@link #conclusionFromPackage}）对照 NATIVE 生产 run（claims
     * 投影），落 V32 一行（shadowExecRef = {@code holmes-shadow-worker}）。
     *
     * <p>与 {@link #compareHolmesNative} 的差异仅在 holmes 侧结论来源（预铸 Map
     * 而非 rca_report 行）；snapshot/比较口径/uq_ec_pair 幂等完全一致。
     */
    public ComparisonOutcome compareShadowHolmesNative(UUID shadowHolmesRunId,
            UUID nativeRunId, String shadowExecRef, Map<String, Object> holmesConclusion) {
        if (shadowExecRef == null || shadowExecRef.isBlank()) {
            throw new IllegalArgumentException("shadowExecRef 必填（影子执行审计引用）");
        }
        Objects.requireNonNull(holmesConclusion, "holmesConclusion");
        RcaRun holmes = runs.findById(shadowHolmesRunId).orElseThrow(() ->
                new IllegalArgumentException("holmes 影子 run 不存在: " + shadowHolmesRunId));
        runs.findById(nativeRunId).orElseThrow(() ->
                new IllegalArgumentException("native run 不存在: " + nativeRunId));
        String snapshotDigest = holmes.investigationHash().hex();
        String candidateDigest = bundles.activeDigest().map(Digest::hex).orElse("");

        Map<String, Object> holmesOutcome = baseOutcome("HOLMES", holmes);
        holmesOutcome.putAll(holmesConclusion);
        Map<String, Object> nativeOutcome = nativeOutcome(nativeRunId);
        List<Map<String, Object>> flags = disagreeFlags(holmesOutcome, nativeOutcome);

        String comparisonKey = Digest.sha256Of(String.join("\n",
                shadowHolmesRunId.toString(), nativeRunId.toString(),
                snapshotDigest, candidateDigest)).hex();
        boolean recorded = store.append(new EngineComparisonRepository.ComparisonRow(
                nativeRunId, comparisonKey, shadowExecRef, snapshotDigest,
                holmesOutcome, nativeOutcome, flags, null, costCompare(
                holmesOutcome, nativeOutcome)));
        metrics.engineComparison(!flags.isEmpty());
        List<String> dims = flags.stream()
                .map(f -> String.valueOf(f.get("dim")))
                .distinct()
                .toList();
        return new ComparisonOutcome(nativeRunId, comparisonKey, dims, recorded);
    }

    /**
     * M6-05 底噪校准：同 snapshot digest 双 Holmes（基线 = 对照期影子结论，
     * 对照行 = {@code compareShadowHolmesNative} 所落；对照执行 = 本行
     * calibrationRun）→ 底噪结论落本行 noise_baseline jsonb（V32 M6-02 恒 null
     * 列的回填面）。行锚仍挂原 native_run_id（对照/校准家族按 native run 聚合，
     * M6-06 差异台账一次取数）。engine 标签：holmes 侧 HOLMES / 校准侧
     * HOLMES_CALIB（诚实区分双侧身份）。
     */
    public ComparisonOutcome compareHolmesCalibration(UUID nativeRunId, UUID calibrationRunId,
            String shadowExecRef, Map<String, Object> baselineConclusion,
            Map<String, Object> calibrationConclusion) {
        if (shadowExecRef == null || shadowExecRef.isBlank()) {
            throw new IllegalArgumentException("shadowExecRef 必填（影子执行审计引用）");
        }
        Objects.requireNonNull(baselineConclusion, "baselineConclusion");
        Objects.requireNonNull(calibrationConclusion, "calibrationConclusion");
        UUID baselineRunId = UUID.fromString(String.valueOf(baselineConclusion.get("run_id")));
        RcaRun baseline = runs.findById(baselineRunId).orElseThrow(() ->
                new IllegalArgumentException("holmes 基线 run 不存在: " + baselineRunId));
        RcaRun calibration = runs.findById(calibrationRunId).orElseThrow(() ->
                new IllegalArgumentException("holmes 校准 run 不存在: " + calibrationRunId));
        String snapshotDigest = baseline.investigationHash().hex();
        String candidateDigest = bundles.activeDigest().map(Digest::hex).orElse("");

        Map<String, Object> baselineOutcome = baseOutcome("HOLMES", baseline);
        baselineOutcome.putAll(baselineConclusion);
        Map<String, Object> calibrationOutcome = baseOutcome("HOLMES_CALIB", calibration);
        calibrationOutcome.putAll(calibrationConclusion);
        List<Map<String, Object>> flags = disagreeFlags(baselineOutcome, calibrationOutcome);
        List<String> dims = flags.stream()
                .map(f -> String.valueOf(f.get("dim")))
                .distinct()
                .toList();

        Map<String, Object> noiseBaseline = new LinkedHashMap<>();
        noiseBaseline.put("native_run_id", nativeRunId.toString());
        noiseBaseline.put("baseline_run_id", baselineRunId.toString());
        noiseBaseline.put("calibration_run_id", calibrationRunId.toString());
        noiseBaseline.put("snapshot_digest", snapshotDigest);
        noiseBaseline.put("disagree", !flags.isEmpty());
        noiseBaseline.put("dims", dims);

        String comparisonKey = Digest.sha256Of(String.join("\n",
                baselineRunId.toString(), calibrationRunId.toString(),
                snapshotDigest, candidateDigest)).hex();
        boolean recorded = store.append(new EngineComparisonRepository.ComparisonRow(
                nativeRunId, comparisonKey, shadowExecRef, snapshotDigest,
                baselineOutcome, calibrationOutcome, flags, noiseBaseline, costCompare(
                baselineOutcome, calibrationOutcome)));
        metrics.engineComparison(!flags.isEmpty());
        return new ComparisonOutcome(nativeRunId, comparisonKey, dims, recorded);
    }

    /**
     * M6-05 执行 artifact → 结论 Map（Worker 预铸面，零报告纪律）：只含结论键
     * （validation_status/total_tokens/claims/claim_keys/root_cause）；缺件诚实
     * 留缺（disagreeFlags 缺数不标记）。engine/run_id/latency 由仓储按 DB run 行补。
     */
    public static Map<String, Object> conclusionFromPackage(ValidationStatus status,
            Integer totalTokens, EvidencePackageV2 typedPackage) {
        Map<String, Object> conclusion = new LinkedHashMap<>();
        if (status != null) {
            conclusion.put("validation_status", status.name());
        }
        if (totalTokens != null) {
            conclusion.put("total_tokens", totalTokens);
        }
        if (typedPackage != null) {
            putConclusionFromPackage(conclusion, typedPackage);
        }
        return conclusion;
    }

    // ---------------------------------------------------------- 双侧归一化

    /** HOLMES 侧归一化结论（报告行 + v2 包解析；缺失/解析失败诚实封装不抛出） */
    private Map<String, Object> holmesOutcome(RcaRun holmes) {
        Map<String, Object> outcome = baseOutcome("HOLMES", holmes);
        RcaReport report = reports.findByRunId(holmes.id()).stream()
                .max(java.util.Comparator.comparing(RcaReport::createdAt))
                .orElse(null);
        if (report == null) {
            outcome.put("report_missing", true);
            return outcome;
        }
        outcome.put("validation_status", report.validationStatus().name());
        outcome.put("total_tokens", report.totalTokens());
        JsonNode analysis = parseAnalysis(report.packageJson());
        if (analysis == null) {
            outcome.put("parse_error", "package_json 非 v2 包形状");
            return outcome;
        }
        putPackageConclusion(outcome, analysis);
        return outcome;
    }

    /** Native 侧（影子）归一化结论：claims 活跃投影为结论源（零报告纪律 → validation/tokens 恒缺） */
    private Map<String, Object> nativeOutcome(UUID nativeRunId) {
        RcaRun nativeRun = runs.findById(nativeRunId).orElseThrow();
        Map<String, Object> outcome = baseOutcome("NATIVE", nativeRun);
        List<ClaimStore.ClaimRow> active = claims.findByRunId(nativeRunId).stream()
                .filter(row -> row.lifecycle() == ClaimLifecycle.ACTIVE)
                .toList();
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("total", (long) active.size());
        for (ClaimStatus status : List.of(ClaimStatus.TRUE, ClaimStatus.FALSE,
                ClaimStatus.UNKNOWN)) {
            counts.put(status.name(), active.stream()
                    .filter(row -> row.status() == status).count());
        }
        outcome.put("claims", counts);
        Set<String> keys = new TreeSet<>();
        for (ClaimStore.ClaimRow row : active) {
            keys.add(row.claimKey());
        }
        outcome.put("claim_keys", List.copyOf(keys));
        outcome.put("root_cause", rootCauseFromClaims(active));
        return outcome;
    }

    private static Map<String, Object> baseOutcome(String engine, RcaRun run) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("engine", engine);
        outcome.put("run_id", run.id().toString());
        outcome.put("latency_ms", latencyMs(run));
        return outcome;
    }

    /** v2 包结论（claims 计数/键集/根因三元组）——holmes/native 同形状装配 */
    private static void putPackageConclusion(Map<String, Object> outcome, JsonNode analysis) {
        EvidencePackageV2 pkg;
        try {
            pkg = EvidencePackageV2.fromMap(com.objwww.pr.control.alert.application.EvidencePackageJsonCodec.toMap(analysis));
        } catch (IllegalArgumentException e) {
            outcome.put("parse_error", e.getMessage());
            return;
        }
        putConclusionFromPackage(outcome, pkg);
    }

    /** v2 包（已类型化）→ 结论键装配（conclusionFromPackage 的共用体） */
    private static void putConclusionFromPackage(Map<String, Object> outcome,
            EvidencePackageV2 pkg) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("total", (long) pkg.claims().size());
        for (ClaimStatus status : List.of(ClaimStatus.TRUE, ClaimStatus.FALSE,
                ClaimStatus.UNKNOWN)) {
            counts.put(status.name(), pkg.claims().stream()
                    .filter(c -> c.status() == status).count());
        }
        outcome.put("claims", counts);
        Set<String> keys = new TreeSet<>();
        for (var claim : pkg.claims()) {
            keys.add(claim.claimType());
        }
        outcome.put("claim_keys", List.copyOf(keys));
        Map<String, Object> rootCause = new LinkedHashMap<>();
        rootCause.put("component", pkg.rootCause().component());
        rootCause.put("fault_type", pkg.rootCause().faultType());
        rootCause.put("reason_code", pkg.rootCause().reasonCode());
        outcome.put("root_cause", rootCause);
    }

    /** 影子侧根因：首个 TRUE 活跃 claim（scope/claimKey/reason），无确认根因诚实 unknown */
    private static Map<String, Object> rootCauseFromClaims(
            List<ClaimStore.ClaimRow> active) {
        Map<String, Object> rootCause = new LinkedHashMap<>();
        var root = active.stream()
                .filter(row -> row.status() == ClaimStatus.TRUE)
                .findFirst();
        if (root.isPresent()) {
            rootCause.put("component", root.get().scope());
            rootCause.put("fault_type", root.get().claimKey());
            rootCause.put("reason_code", root.get().reason());
        } else {
            rootCause.put("component", UNKNOWN_COMPONENT);
            rootCause.put("fault_type", UNRESOLVED_FAULT_TYPE);
            rootCause.put("reason_code", NO_ROOT_CAUSE_REASON);
        }
        return rootCause;
    }

    /** 外层剥壳：{"engine":...,"analysis":{v2包}} 取内层；直接 v2 包原样返回 */
    private JsonNode parseAnalysis(String packageJson) {
        if (packageJson == null || packageJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(packageJson);
            JsonNode analysis = root.path("analysis");
            return analysis.isObject() ? analysis : (root.isObject() ? root : null);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------- 差异标记

    /** 六维差异标记：双侧同位数据齐且不等才标记（缺数 ≠ 差异，无 GT 不判对错） */
    private static List<Map<String, Object>> disagreeFlags(Map<String, Object> holmes,
            Map<String, Object> nativeOutcome) {
        List<Map<String, Object>> flags = new ArrayList<>();

        // result：holmes 结论可解析且 native 有 claims 面时比较根因三元组 + claim 键集
        if (!holmes.containsKey("report_missing") && !holmes.containsKey("parse_error")
                && holmes.containsKey("root_cause")) {
            Map<String, Object> hRoot = cast(holmes.get("root_cause"));
            Map<String, Object> nRoot = cast(nativeOutcome.get("root_cause"));
            Set<String> hKeys = keys(holmes.get("claim_keys"));
            Set<String> nKeys = keys(nativeOutcome.get("claim_keys"));
            if (!hRoot.equals(nRoot) || !hKeys.equals(nKeys)) {
                Map<String, Object> flag = new LinkedHashMap<>();
                flag.put("dim", DIM_RESULT);
                flag.put("holmes", Map.of("root_cause", hRoot, "claim_keys", hKeys));
                flag.put("native", Map.of("root_cause", nRoot, "claim_keys", nKeys));
                flags.add(flag);
            }
        }

        flagOnDifference(flags, DIM_SCHEMA,
                holmes.get("validation_status"), nativeOutcome.get("validation_status"));
        flagOnDifference(flags, DIM_COST,
                holmes.get("total_tokens"), nativeOutcome.get("total_tokens"));
        flagOnDifference(flags, DIM_LATENCY,
                holmes.get("latency_ms"), nativeOutcome.get("latency_ms"));
        // tool_legality / safety_violation：M6-02 接线点无逐 run 观察面，缺数不标记（M6-05 启用）
        return flags;
    }

    private static void flagOnDifference(List<Map<String, Object>> flags, String dim,
            Object holmesValue, Object nativeValue) {
        if (holmesValue == null || nativeValue == null) {
            return;
        }
        if (holmesValue.equals(nativeValue)) {
            return;
        }
        Map<String, Object> flag = new LinkedHashMap<>();
        flag.put("dim", dim);
        flag.put("holmes", holmesValue);
        flag.put("native", nativeValue);
        flags.add(flag);
    }

    /** 成本对照（双侧同位键，缺侧省略——不造 null 键） */
    private static Map<String, Object> costCompare(Map<String, Object> holmes,
            Map<String, Object> nativeOutcome) {
        Map<String, Object> cost = new LinkedHashMap<>();
        if (holmes.get("total_tokens") != null) {
            cost.put("holmes_total_tokens", holmes.get("total_tokens"));
        }
        if (nativeOutcome.get("total_tokens") != null) {
            cost.put("native_total_tokens", nativeOutcome.get("total_tokens"));
        }
        if (holmes.get("latency_ms") != null) {
            cost.put("holmes_latency_ms", holmes.get("latency_ms"));
        }
        if (nativeOutcome.get("latency_ms") != null) {
            cost.put("native_latency_ms", nativeOutcome.get("latency_ms"));
        }
        return cost;
    }

    private static Long latencyMs(RcaRun run) {
        if (run.startedAt() == null || run.finishedAt() == null) {
            return null;
        }
        long millis = Duration.between(run.startedAt(), run.finishedAt()).toMillis();
        return millis >= 0 ? millis : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    private static Set<String> keys(Object value) {
        Set<String> out = new LinkedHashSet<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }
}
