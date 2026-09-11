package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.model.EvalComparisonRecord;
import com.objwww.pr.control.eval.domain.repository.EvalComparisonRepository;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CompareCaseRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CompareRunMeta;
import com.objwww.pr.control.eval.domain.service.PairedTrialStats;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * EV-07 配对工作台查询/落档服务（/api/eval/compare + /api/eval/comparisons 面；
 * 方案 §3.5/§5.3 "GET /api/eval/comparisons……缺持久化先补记录"）。SQL 归
 * {@link EvalQueryReader}/{@link EvalComparisonRepository}，本类只承担参数校验、
 * 游标编解码、配对/统计/门编排（纯函数段归 {@link EvalCompare}）与 DTO 装配——
 * 假端口可测，沿 EvalQueryService 单测模式。
 *
 * <p>语义冻结：
 * <ul>
 *   <li>comparable=false → 不出配对结论（summary/cases/unpaired/stats 全空），
 *       门 NOT_EVALUABLE——前端只做展示（EU23/EU25）；</li>
 *   <li>改善/退化/持平计数为 RatioStat 三件套（分母 = pairedCount，分母 0 →
 *       NOT_APPLICABLE，EvalQueryService 同律）；</li>
 *   <li>GET 全读面：实时计算 + 同对最新落档引用（无落档如实 null）；POST 落档 =
 *       同一计算的 insert-only 快照（V85，重落档换新 id，不覆盖）；</li>
 *   <li>有界面：单侧扫描闸 {@value #MAX_SCAN_ROWS_PER_RUN} 行（超出 → scanTruncated
 *       + 门 INCONCLUSIVE）；逐例列表游标分页（group 过滤 + (scenarioId, roundNo)
 *       键集）；unpaired 列表上限 {@value #MAX_UNPAIRED_LISTED}（超出截断标记）。</li>
 * </ul>
 */
public class EvalCompareService {

    /** 单侧案例扫描闸（超出 = 读面截断，门转 INCONCLUSIVE 不出资格结论） */
    static final int MAX_SCAN_ROWS_PER_RUN = 10_000;
    /** unpaired 列表输出上限（计数仍按全集如实） */
    static final int MAX_UNPAIRED_LISTED = 500;

    private static final Set<String> GROUPS = Set.of("ALL", EvalCompare.GROUP_IMPROVED,
            EvalCompare.GROUP_REGRESSED, EvalCompare.GROUP_FLAT);

    private final EvalQueryReader reader;
    private final EvalComparisonRepository comparisons;
    private final ObjectMapper mapper;

    public EvalCompareService(EvalQueryReader reader, EvalComparisonRepository comparisons,
                              ObjectMapper mapper) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.comparisons = Objects.requireNonNull(comparisons, "comparisons");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    // ------------------------------------------------------------------ DTO（record，字段名即 JSON 契约）

    /** 单侧案例投影（逐例表的基线/候选列；contentDigest 可空 = 身份未解析如实） */
    public record SideCase(UUID caseExecutionId, String verdict, boolean rootCauseHit,
                           String expectedRootCause, String selectionPolicyVersion,
                           String contentDigest) {
    }

    /** 逐例对比行（group ∈ IMPROVED/REGRESSED/FLAT；differenceNote 可空 = 持平同形无噪声） */
    public record CompareCaseItem(String scenarioId, int roundNo, String group,
                                  SideCase baseline, SideCase candidate,
                                  String verdictChange, String differenceNote,
                                  String inputDigestMatch, String clusterId,
                                  String faultType) {
    }

    /** 未配对行（side ∈ BASELINE_ONLY/CANDIDATE_ONLY；reason = 机器码） */
    public record UnpairedItem(String scenarioId, int roundNo, String side, String reason,
                               UUID caseExecutionId) {
    }

    /** PairedTrialStats 溯源块（INV-AM5-3 全记录面；INCONCLUSIVE 时区间 null 如实悬挂） */
    public record StatsBlock(double pointEstimate, Double ciLower, Double ciUpper,
                             int clusterCount, long statsSeed, String algorithmVersion,
                             int resamples, String ciMethod, String verdict) {
    }

    /** 对比质量门块（ruleVersion/阈值随响应携带 = 复现锚；reasons 机器码） */
    public record GateBlock(String ruleVersion, String outcome, List<String> reasons,
                            double maxRegressionRate, double ciMargin) {
    }

    /** 同对最新落档引用（无落档 → 整块 null 如实） */
    public record GateRecordRef(UUID recordId, String outcome, Instant createdAt) {
    }

    /** 配对汇总（计数三件套分母 = pairedCount；matrix = 判定变化矩阵 基线判定→候选判定） */
    public record CompareSummary(int pairedCount, int unpairedCount,
                                 EvalQueryService.RatioStat improved,
                                 EvalQueryService.RatioStat regressed,
                                 EvalQueryService.RatioStat flat,
                                 Map<String, Integer> verdictChangeMatrix,
                                 List<EvalCompare.ClusterStat> clusters,
                                 List<EvalCompare.FaultTypeStat> byFaultType,
                                 StatsBlock stats) {
    }

    /** 可比性块（dimensions 含严格面与信息面；mismatches = 不一致严格维度名） */
    public record ComparabilityBlock(boolean comparable,
                                     List<EvalCompare.Dimension> dimensions,
                                     List<String> mismatches) {
    }

    /**
     * 对比响应（GET 实时面 / POST 落档面同构）。comparable=false 时 summary=null、
     * cases/unpaired 空表、gate=NOT_EVALUABLE——不出配对结论。
     */
    public record EvalCompareResponse(UUID baselineRunId, UUID candidateRunId,
                                      ComparabilityBlock comparability, CompareSummary summary,
                                      GateBlock gate, GateRecordRef gateRecord,
                                      List<CompareCaseItem> cases,
                                      List<UnpairedItem> unpaired,
                                      boolean unpairedTruncated, boolean scanTruncated,
                                      String nextCursor, Instant asOf) {
    }

    // ------------------------------------------------------------------ 查询面（GET）

    /**
     * 实时对比投影（全读面）。参数非法 → IllegalArgumentException（400 面）；
     * 任一 run 未知 → empty（404 面）。
     */
    public Optional<EvalCompareResponse> compare(UUID baselineRunId, UUID candidateRunId,
                                                 String group, String cursor, int limit) {
        Computation computation = compute(baselineRunId, candidateRunId, group, cursor, limit);
        if (computation == null) {
            return Optional.empty();
        }
        return Optional.of(computation.response(mapper,
                latestGateRecord(baselineRunId, candidateRunId)));
    }

    // ------------------------------------------------------------------ 落档面（POST）

    /**
     * 计算并落档（insert-only 快照；落档后响应携带本行 recordId）。
     * 任一 run 未知 → empty（404 面）；baseline=candidate → IllegalArgumentException（400 面）。
     */
    public Optional<EvalCompareResponse> record(UUID baselineRunId, UUID candidateRunId,
                                                String actor) {
        Computation computation = compute(baselineRunId, candidateRunId, "ALL", null,
                MAX_SCAN_ROWS_PER_RUN);
        if (computation == null) {
            return Optional.empty();
        }
        EvalComparisonRecord record = new EvalComparisonRecord(UUID.randomUUID(),
                baselineRunId, candidateRunId, computation.comparability().comparable(),
                toJson(computation.comparability().dimensions()),
                computation.pairing() == null ? 0 : computation.pairing().pairs().size(),
                computation.pairing() == null ? 0 : computation.pairing().unpaired().size(),
                computation.counts()[1], computation.counts()[2], computation.counts()[3],
                computation.stats() == null ? null : toJson(statsBlock(computation.stats())),
                computation.gate().outcome(), computation.gate().reasons(),
                computation.gate().ruleVersion(), actor, Instant.now());
        comparisons.insert(record);
        return Optional.of(computation.response(mapper,
                new GateRecordRef(record.id(), record.gateOutcome(), record.createdAt())));
    }

    // ------------------------------------------------------------------ 编排（纯函数段归 EvalCompare）

    /** 一次计算的中间态（GET/POST 同一路径，避免两套口径漂移） */
    private record Computation(CompareRunMeta baseline, CompareRunMeta candidate,
                               EvalCompare.Comparability comparability,
                               EvalCompare.Pairing pairing, int[] counts,
                               PairedTrialStats.StatsResult stats,
                               EvalCompare.GateResult gate, boolean scanTruncated,
                               String group, Keyset keyset, int limit) {

        EvalCompareResponse response(ObjectMapper mapper, GateRecordRef persistedRef) {
            ComparabilityBlock comparabilityBlock = new ComparabilityBlock(
                    comparability.comparable(), comparability.dimensions(),
                    comparability.mismatches());
            GateBlock gateBlock = new GateBlock(gate.ruleVersion(), gate.outcome(),
                    gate.reasons(), EvalCompare.MAX_REGRESSION_RATE, EvalCompare.CI_MARGIN);
            if (!comparability.comparable()) {
                return new EvalCompareResponse(baseline.runId(), candidate.runId(),
                        comparabilityBlock, null, gateBlock, persistedRef,
                        List.of(), List.of(), false, false, null, Instant.now());
            }
            CompareSummary summary = new CompareSummary(pairing.pairs().size(),
                    pairing.unpaired().size(),
                    ratio(counts[1], pairing.pairs().size()),
                    ratio(counts[2], pairing.pairs().size()),
                    ratio(counts[3], pairing.pairs().size()),
                    pairing.verdictChangeMatrix(), pairing.clusters(), pairing.byFaultType(),
                    stats == null ? null : statsBlock(stats));
            Slice slice = sliceCases(mapper, pairing.pairs(), group, keyset, limit);
            List<UnpairedItem> unpairedItems = new ArrayList<>(
                    Math.min(pairing.unpaired().size(), MAX_UNPAIRED_LISTED));
            for (EvalCompare.UnpairedCase u : pairing.unpaired()) {
                if (unpairedItems.size() >= MAX_UNPAIRED_LISTED) {
                    break;
                }
                unpairedItems.add(new UnpairedItem(u.scenarioId(), u.roundNo(), u.side(),
                        u.reason(), u.caseExecutionId()));
            }
            return new EvalCompareResponse(baseline.runId(), candidate.runId(),
                    comparabilityBlock, summary, gateBlock, persistedRef,
                    slice.items(), List.copyOf(unpairedItems),
                    pairing.unpaired().size() > MAX_UNPAIRED_LISTED, scanTruncated,
                    slice.nextCursor(), Instant.now());
        }
    }

    /** 计算主路径：元数据 → 可比性（不过即短路）→ 案例扫描 → 配对/统计 → 门 */
    private Computation compute(UUID baselineRunId, UUID candidateRunId, String group,
                                String cursor, int limit) {
        if (baselineRunId.equals(candidateRunId)) {
            throw new IllegalArgumentException("baseline 与 candidate 不得为同一 run");
        }
        String effectiveGroup = group == null || group.isBlank() ? "ALL" : group;
        if (!GROUPS.contains(effectiveGroup)) {
            throw new IllegalArgumentException("group 必为 " + GROUPS + ": " + group);
        }
        Keyset keyset = parseCursor(cursor);
        Optional<CompareRunMeta> baseline = reader.findCompareMeta(baselineRunId);
        Optional<CompareRunMeta> candidate = reader.findCompareMeta(candidateRunId);
        if (baseline.isEmpty() || candidate.isEmpty()) {
            return null;
        }
        List<CompareCaseRow> baselineCases =
                reader.listCasesForCompare(baselineRunId, MAX_SCAN_ROWS_PER_RUN + 1);
        List<CompareCaseRow> candidateCases =
                reader.listCasesForCompare(candidateRunId, MAX_SCAN_ROWS_PER_RUN + 1);
        boolean scanTruncated = baselineCases.size() > MAX_SCAN_ROWS_PER_RUN
                || candidateCases.size() > MAX_SCAN_ROWS_PER_RUN;
        if (scanTruncated) {
            baselineCases = baselineCases.subList(0,
                    Math.min(baselineCases.size(), MAX_SCAN_ROWS_PER_RUN));
            candidateCases = candidateCases.subList(0,
                    Math.min(candidateCases.size(), MAX_SCAN_ROWS_PER_RUN));
        }
        EvalCompare.Comparability comparability = EvalCompare.comparability(
                baseline.get(), candidate.get(),
                policyVersions(baselineCases), policyVersions(candidateCases));
        if (!comparability.comparable()) {
            EvalCompare.GateResult gate = EvalCompare.gate(false, false, 0, 0, null);
            return new Computation(baseline.get(), candidate.get(), comparability, null,
                    new int[4], null, gate, false, effectiveGroup, keyset, limit);
        }
        long seed = EvalCompare.statsSeed(baselineRunId, candidateRunId);
        EvalCompare.Pairing pairing = EvalCompare.pair(mapper, baselineCases, candidateCases,
                seed);
        int[] counts = new int[4];
        for (EvalCompare.PairedCase p : pairing.pairs()) {
            counts[0]++;
            switch (p.group()) {
                case EvalCompare.GROUP_IMPROVED -> counts[1]++;
                case EvalCompare.GROUP_REGRESSED -> counts[2]++;
                default -> counts[3]++;
            }
        }
        EvalCompare.GateResult gate = EvalCompare.gate(true, scanTruncated,
                pairing.pairs().size(), counts[2], pairing.stats());
        return new Computation(baseline.get(), candidate.get(), comparability, pairing,
                counts, pairing.stats(), gate, scanTruncated, effectiveGroup, keyset, limit);
    }

    /** 逐案例 selection_policy_version 去重升序集（评分器语义锚维度） */
    private static List<String> policyVersions(List<CompareCaseRow> rows) {
        Set<String> versions = new TreeSet<>();
        for (CompareCaseRow row : rows) {
            versions.add(row.selectionPolicyVersion());
        }
        return List.copyOf(versions);
    }

    // ------------------------------------------------------------------ 逐例切片（group 过滤 + 键集游标）

    private record Keyset(String scenarioId, int roundNo) {
    }

    private record Slice(List<CompareCaseItem> items, String nextCursor) {
    }

    private static Slice sliceCases(ObjectMapper mapper, List<EvalCompare.PairedCase> pairs,
                                    String group, Keyset keyset, int limit) {
        List<CompareCaseItem> items = new ArrayList<>(Math.min(limit, pairs.size()));
        String nextCursor = null;
        for (EvalCompare.PairedCase p : pairs) {
            if (!"ALL".equals(group) && !group.equals(p.group())) {
                continue;
            }
            if (keyset != null && (p.scenarioId().compareTo(keyset.scenarioId()) < 0
                    || (p.scenarioId().equals(keyset.scenarioId())
                    && p.roundNo() <= keyset.roundNo()))) {
                continue;
            }
            if (items.size() >= limit) {
                nextCursor = items.get(items.size() - 1).scenarioId()
                        + "/" + items.get(items.size() - 1).roundNo();
                break;
            }
            items.add(toItem(mapper, p));
        }
        return new Slice(List.copyOf(items), nextCursor);
    }

    private static CompareCaseItem toItem(ObjectMapper mapper, EvalCompare.PairedCase p) {
        return new CompareCaseItem(p.scenarioId(), p.roundNo(), p.group(),
                side(mapper, p.baseline()), side(mapper, p.candidate()), p.verdictChange(),
                p.differenceNote(), p.inputDigestMatch(), p.clusterId(), p.faultType());
    }

    private static SideCase side(ObjectMapper mapper, CompareCaseRow row) {
        return new SideCase(row.caseExecutionId(), row.verdict(), row.rootCauseHit(),
                EvalQueryService.summarizeRootCause(mapper, row.expectedRootCauseJson()),
                row.selectionPolicyVersion(), row.contentDigest());
    }

    /** cursor = <scenarioId>/<roundNo>（沿 cases 游标同式，按最后一个 "/" 切分） */
    private static Keyset parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        int slash = cursor.lastIndexOf('/');
        if (slash <= 0 || slash == cursor.length() - 1) {
            throw new IllegalArgumentException("cursor 非法（期形 <scenarioId>/<roundNo>）");
        }
        try {
            return new Keyset(cursor.substring(0, slash),
                    Integer.parseInt(cursor.substring(slash + 1)));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cursor 非法（期形 <scenarioId>/<roundNo>）");
        }
    }

    // ------------------------------------------------------------------ 内部

    private static EvalQueryService.RatioStat ratio(int numerator, int denominator) {
        if (denominator == 0) {
            return new EvalQueryService.RatioStat((long) numerator, 0L, "NOT_APPLICABLE");
        }
        return new EvalQueryService.RatioStat((long) numerator, (long) denominator, "OK");
    }

    private static StatsBlock statsBlock(PairedTrialStats.StatsResult stats) {
        return new StatsBlock(stats.pointEstimate(), stats.ciLower(), stats.ciUpper(),
                stats.clusterCount(), stats.statsSeed(), stats.algorithmVersion(),
                stats.resamples(), stats.ciMethod(), stats.verdict().name());
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("落档快照序列化失败", e);
        }
    }

    /** GET 面的同对最新落档引用（无落档如实 null） */
    public GateRecordRef latestGateRecord(UUID baselineRunId, UUID candidateRunId) {
        return comparisons.findLatestByPair(baselineRunId, candidateRunId)
                .map(r -> new GateRecordRef(r.id(), r.gateOutcome(), r.createdAt()))
                .orElse(null);
    }
}
