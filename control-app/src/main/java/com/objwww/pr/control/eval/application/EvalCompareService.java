package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.model.EvalComparisonRecord;
import com.objwww.pr.control.eval.domain.repository.EvalComparisonRepository;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CompareCaseRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CompareRunMeta;
import com.objwww.pr.control.eval.domain.service.PairedTrialStats;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 *   <li>FUP-02（门 v2）：EvidenceReadiness 区分"能展示差异"与"能产生最终结论"——
 *       运行未终态/不完整终态、无冻结计划分母（历史 run 缺 launch_plan 快照降级
 *       UNAVAILABLE）、计划案例缺失、身份未核验 → 最终门只出 INCONCLUSIVE，差异
 *       照常展示（暂态分析）；新落档带 readiness 快照（V110），v1 历史行不改写。</li>
 * </ul>
 *
 * <p>R12 逐例差值（EV-07+）：配对双方按 caseExecutionId 稳定投影间直接作差——
 * Δscore（rootCauseHit 0/1，higher-better）/ Δcost（微积分，仅双方链路完全
 * priced，R4/EU20 契约：unpriced/usage_missing/跨币种不折算不为 0）/ Δlatency
 * （lower-better）；每指标带"高分是否更好"方向声明，缺值如实 UNKNOWN。
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

    /**
     * 单指标差值（R12/EV-07+）：direction = 该指标"高分是否更好"的显式声明；
     * delta 为 null = 该侧缺值如实（group=UNKNOWN，不猜 0——R4 同律）。
     */
    public record MetricDelta(Long delta, String direction, String group) {

        public static final String HIGHER_IS_BETTER = "HIGHER_IS_BETTER";
        public static final String LOWER_IS_BETTER = "LOWER_IS_BETTER";
    }

    /**
     * 逐例差值（R12）：score = rootCauseHit 0/1 差（恒可算）；cost = 微分——仅双方
     * 链路完全 priced（R4/EU20：unpriced/usage_missing/跨币种不折算不为 0，costNote
     * 机器码留痕）；latency = eval_case_result.latency_ms 差（任一侧缺 → UNKNOWN）。
     */
    public record CaseDelta(MetricDelta score, MetricDelta cost, MetricDelta latency,
                            String costNote) {
    }

    /** 逐例对比行（group ∈ IMPROVED/REGRESSED/FLAT；differenceNote 可空 = 持平同形无噪声） */
    public record CompareCaseItem(String scenarioId, int roundNo, String group,
                                  SideCase baseline, SideCase candidate,
                                  String verdictChange, String differenceNote,
                                  String inputDigestMatch, String clusterId,
                                  String faultType, CaseDelta delta) {
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

    /** 同对最新落档引用（无落档 → 整块 null 如实；ruleVersion 供前端标"历史规则"） */
    public record GateRecordRef(UUID recordId, String outcome, String ruleVersion,
                                Instant createdAt) {
    }

    /** FUP-02 缺失案例项（reason 机器码：MISSING_IN_BASELINE/MISSING_IN_CANDIDATE/
     *  INPUT_DIGEST_MISMATCH/MISSING_IN_BOTH） */
    public record MissingCaseItem(String scenarioId, int roundNo, String reason) {
    }

    /**
     * FUP-02 证据就绪度块（区分"能展示差异"与"能产生最终结论"）：planSetSource =
     * LAUNCH_PLAN（冻结计划键集分母）/ UNAVAILABLE（历史 run 无 launch_plan 快照，
     * 明确降级——expected/completed/missing/unexpected 如实 null，不拿双侧并集冒充
     * 计划完整）；coverageRatio = completed/expected（分母 0/未知 → null）。
     */
    public record ReadinessBlock(String baselineState, String candidateState,
                                 String planSetSource, Integer expectedCount,
                                 Integer completedCount, int pairedCount, int verifiedCount,
                                 int unverifiedCount, Integer missingCount,
                                 Integer unexpectedCount, Double coverageRatio,
                                 List<MissingCaseItem> missingCases,
                                 boolean missingTruncated) {
    }

    /** FUP-02 落档就绪度快照（V110 readiness_snapshot 列原文；v1 历史行 NULL 不回填） */
    public record ReadinessSnapshot(String ruleVersion, double maxRegressionRate,
                                    double ciMargin, ReadinessBlock readiness) {
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
     * readiness=null、cases/unpaired 空表、gate=NOT_EVALUABLE——不出配对结论。
     * readiness（FUP-02）= 证据就绪度：暂态分析（运行未终态等）下差异照常展示，
     * 最终门结论只认 gate。
     */
    public record EvalCompareResponse(UUID baselineRunId, UUID candidateRunId,
                                      ComparabilityBlock comparability, CompareSummary summary,
                                      GateBlock gate, GateRecordRef gateRecord,
                                      ReadinessBlock readiness,
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
                computation.readinessSnapshotJson(mapper),
                computation.gate().outcome(), computation.gate().reasons(),
                computation.gate().ruleVersion(), actor, Instant.now());
        comparisons.insert(record);
        return Optional.of(computation.response(mapper,
                new GateRecordRef(record.id(), record.gateOutcome(), record.gateRuleVersion(),
                        record.createdAt())));
    }

    // ------------------------------------------------------------------ 编排（纯函数段归 EvalCompare）

    /** 一次计算的中间态（GET/POST 同一路径，避免两套口径漂移） */
    private record Computation(CompareRunMeta baseline, CompareRunMeta candidate,
                               EvalCompare.Comparability comparability,
                               EvalCompare.Pairing pairing, int[] counts,
                               PairedTrialStats.StatsResult stats,
                               EvalCompare.EvidenceReadiness readiness,
                               EvalCompare.GateResult gate, boolean scanTruncated,
                               String group, Keyset keyset, int limit,
                               Map<UUID, List<EvalQueryReader.UsageCallRow>> usageByRcaRun) {

        EvalCompareResponse response(ObjectMapper mapper, GateRecordRef persistedRef) {
            ComparabilityBlock comparabilityBlock = new ComparabilityBlock(
                    comparability.comparable(), comparability.dimensions(),
                    comparability.mismatches());
            GateBlock gateBlock = new GateBlock(gate.ruleVersion(), gate.outcome(),
                    gate.reasons(), EvalCompare.MAX_REGRESSION_RATE, EvalCompare.CI_MARGIN);
            if (!comparability.comparable()) {
                return new EvalCompareResponse(baseline.runId(), candidate.runId(),
                        comparabilityBlock, null, gateBlock, persistedRef, null,
                        List.of(), List.of(), false, false, null, Instant.now());
            }
            CompareSummary summary = new CompareSummary(pairing.pairs().size(),
                    pairing.unpaired().size(),
                    ratio(counts[1], pairing.pairs().size()),
                    ratio(counts[2], pairing.pairs().size()),
                    ratio(counts[3], pairing.pairs().size()),
                    pairing.verdictChangeMatrix(), pairing.clusters(), pairing.byFaultType(),
                    stats == null ? null : statsBlock(stats));
            Slice slice = sliceCases(mapper, pairing.pairs(), group, keyset, limit,
                    usageByRcaRun);
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
                    readinessBlock(), slice.items(), List.copyOf(unpairedItems),
                    pairing.unpaired().size() > MAX_UNPAIRED_LISTED, scanTruncated,
                    slice.nextCursor(), Instant.now());
        }

        /** FUP-02 就绪度块装配（缺失清单截断 {@value #MAX_UNPAIRED_LISTED}，
         *  计数仍按全集如实） */
        ReadinessBlock readinessBlock() {
            if (readiness == null) {
                return null;
            }
            List<MissingCaseItem> missing = new ArrayList<>(
                    Math.min(readiness.missingCases().size(), MAX_UNPAIRED_LISTED));
            for (EvalCompare.MissingCase m : readiness.missingCases()) {
                if (missing.size() >= MAX_UNPAIRED_LISTED) {
                    break;
                }
                missing.add(new MissingCaseItem(m.scenarioId(), m.roundNo(), m.reason()));
            }
            Double coverage = readiness.expectedCount() == null
                    || readiness.expectedCount() == 0 ? null
                    : (double) readiness.completedCount() / readiness.expectedCount();
            return new ReadinessBlock(readiness.baselineState(), readiness.candidateState(),
                    readiness.planSetSource(), readiness.expectedCount(),
                    readiness.completedCount(), readiness.pairedCount(),
                    readiness.verifiedCount(), readiness.unverifiedCount(),
                    readiness.missingCount(), readiness.unexpectedCount(), coverage,
                    List.copyOf(missing),
                    readiness.missingCases().size() > MAX_UNPAIRED_LISTED);
        }

        /** FUP-02 落档就绪度快照（规则版本/阈值随快照携带 = 复现锚） */
        String readinessSnapshotJson(ObjectMapper mapper) {
            if (readiness == null) {
                return null;
            }
            try {
                return mapper.writeValueAsString(new ReadinessSnapshot(gate.ruleVersion(),
                        EvalCompare.MAX_REGRESSION_RATE, EvalCompare.CI_MARGIN,
                        readinessBlock()));
            } catch (Exception e) {
                throw new IllegalStateException("落档就绪度快照序列化失败", e);
            }
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
            EvalCompare.GateResult gate = EvalCompare.gate(false, false, null, 0, 0, null);
            return new Computation(baseline.get(), candidate.get(), comparability, null,
                    new int[4], null, null, gate, false, effectiveGroup, keyset, limit,
                    Map.of());
        }
        long seed = EvalCompare.statsSeed(baselineRunId, candidateRunId);
        EvalCompare.Pairing pairing = EvalCompare.pair(mapper, baselineCases, candidateCases,
                seed);
        // FUP-02 证据就绪度：冻结计划分母 + 运行终态 + 配对/身份核验（gate v2 前置分支输入）
        EvalCompare.EvidenceReadiness readiness = EvalCompare.readiness(baseline.get(),
                candidate.get(), baselineCases, candidateCases, pairing,
                frozenPlan(baseline.get(), candidate.get()));
        // R12 逐例差值输入：双 run usage 链一次取回（禁 N+1），按案例 rca_run_id 分组
        Map<UUID, List<EvalQueryReader.UsageCallRow>> usageByRcaRun = new LinkedHashMap<>();
        for (EvalQueryReader.UsageCallRow row : reader.listUsageCallsForRuns(
                List.of(baselineRunId, candidateRunId))) {
            usageByRcaRun.computeIfAbsent(row.rcaRunId(), k -> new ArrayList<>()).add(row);
        }
        int[] counts = new int[4];
        for (EvalCompare.PairedCase p : pairing.pairs()) {
            counts[0]++;
            switch (p.group()) {
                case EvalCompare.GROUP_IMPROVED -> counts[1]++;
                case EvalCompare.GROUP_REGRESSED -> counts[2]++;
                default -> counts[3]++;
            }
        }
        EvalCompare.GateResult gate = EvalCompare.gate(true, scanTruncated, readiness,
                pairing.pairs().size(), counts[2], pairing.stats());
        return new Computation(baseline.get(), candidate.get(), comparability, pairing,
                counts, pairing.stats(), readiness, gate, scanTruncated, effectiveGroup,
                keyset, limit, Map.copyOf(usageByRcaRun));
    }

    /** worker 默认轮次（EvalLaunchPlan 契约：roundsPerScenario 空 = 现有 5×2 编排的 2） */
    private static final int DEFAULT_ROUNDS_PER_SCENARIO = 2;

    /**
     * FUP-02 冻结计划装配：双侧 launch_plan 快照均在且数据集案例键集非空 →
     * LAUNCH_PLAN 分母（键集 × 各侧轮次）；任一缺失（旧 CLI 跑批 launch_plan 为
     * NULL、快照解析失败、版本无可见案例）→ null 如实降级 UNAVAILABLE（不猜轮次、
     * 不拿双侧并集冒充计划完整）。
     */
    private EvalCompare.FrozenPlan frozenPlan(CompareRunMeta baseline,
                                              CompareRunMeta candidate) {
        Integer baselineRounds = planRounds(baseline.launchPlanJson());
        Integer candidateRounds = planRounds(candidate.launchPlanJson());
        if (baselineRounds == null || candidateRounds == null) {
            return null;
        }
        // 可比性已过 = 双侧 datasetVersion 全等，任取一侧
        List<String> keys = reader.listPlanCaseKeys(baseline.datasetVersion());
        if (keys.isEmpty()) {
            return null;
        }
        return new EvalCompare.FrozenPlan(Set.copyOf(keys), baselineRounds, candidateRounds);
    }

    /** launch_plan 快照 → roundsPerScenario（空值 = worker 默认 2，EvalLaunchPlan 契约
     *  同律）；无快照/解析失败 → null（历史面不猜轮次） */
    private Integer planRounds(String launchPlanJson) {
        if (launchPlanJson == null) {
            return null;
        }
        try {
            JsonNode rounds = mapper.readTree(launchPlanJson).path("roundsPerScenario");
            return rounds.isInt() && rounds.asInt() > 0
                    ? rounds.asInt() : DEFAULT_ROUNDS_PER_SCENARIO;
        } catch (Exception e) {
            return null;
        }
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
                                    String group, Keyset keyset, int limit,
                                    Map<UUID, List<EvalQueryReader.UsageCallRow>> usageByRcaRun) {
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
            items.add(toItem(mapper, p, usageByRcaRun));
        }
        return new Slice(List.copyOf(items), nextCursor);
    }

    private static CompareCaseItem toItem(ObjectMapper mapper, EvalCompare.PairedCase p,
            Map<UUID, List<EvalQueryReader.UsageCallRow>> usageByRcaRun) {
        return new CompareCaseItem(p.scenarioId(), p.roundNo(), p.group(),
                side(mapper, p.baseline()), side(mapper, p.candidate()), p.verdictChange(),
                p.differenceNote(), p.inputDigestMatch(), p.clusterId(), p.faultType(),
                delta(p, usageByRcaRun));
    }

    /**
     * R12 逐例差值（按 caseExecutionId 稳定 join 的双侧投影间直接作差，不按时间/顺序）：
     * score 恒可算（hit=1/miss=0，higher-better）；cost 仅双方链路完全 priced（R4：
     * unpriced/usage_missing 不折算不为 0，EU20 跨币种不折算）；latency 任一侧缺如实
     * UNKNOWN。改善 = 候选侧更优（按各指标 direction 判）。
     */
    private static CaseDelta delta(EvalCompare.PairedCase p,
            Map<UUID, List<EvalQueryReader.UsageCallRow>> usageByRcaRun) {
        long scoreDelta = (p.candidate().rootCauseHit() ? 1 : 0)
                - (p.baseline().rootCauseHit() ? 1 : 0);
        MetricDelta score = new MetricDelta(scoreDelta, MetricDelta.HIGHER_IS_BETTER,
                groupOf(scoreDelta, true));

        Long baselineCost = caseCostMicros(p.baseline().rcaRunId(), usageByRcaRun);
        Long candidateCost = caseCostMicros(p.candidate().rcaRunId(), usageByRcaRun);
        MetricDelta cost;
        String costNote;
        if (baselineCost == null || candidateCost == null) {
            cost = new MetricDelta(null, MetricDelta.LOWER_IS_BETTER, "UNKNOWN");
            costNote = baselineCost == null && candidateCost == null
                    ? "BOTH_COST_UNKNOWN" : baselineCost == null
                    ? "BASELINE_COST_UNKNOWN" : "CANDIDATE_COST_UNKNOWN";
        } else {
            long costDelta = candidateCost - baselineCost;
            cost = new MetricDelta(costDelta, MetricDelta.LOWER_IS_BETTER,
                    groupOf(costDelta, false));
            costNote = null;
        }

        Long baselineLatency = p.baseline().latencyMs();
        Long candidateLatency = p.candidate().latencyMs();
        MetricDelta latency;
        if (baselineLatency == null || candidateLatency == null) {
            latency = new MetricDelta(null, MetricDelta.LOWER_IS_BETTER, "UNKNOWN");
        } else {
            long latencyDelta = candidateLatency - baselineLatency;
            latency = new MetricDelta(latencyDelta, MetricDelta.LOWER_IS_BETTER,
                    groupOf(latencyDelta, false));
        }
        return new CaseDelta(score, cost, latency, costNote);
    }

    /** 改善判：higher-better 时 delta>0 改善，lower-better 时 delta<0 改善 */
    private static String groupOf(long delta, boolean higherIsBetter) {
        if (delta == 0) {
            return EvalCompare.GROUP_FLAT;
        }
        boolean better = higherIsBetter ? delta > 0 : delta < 0;
        return better ? EvalCompare.GROUP_IMPROVED : EvalCompare.GROUP_REGRESSED;
    }

    /** 单案例链路费用（R6 usage 链按 rca_run_id 归组；R4：任一行 usage 缺失/unpriced
     * → null 不折算；EU20：链内跨币种 → null 不折算） */
    private static Long caseCostMicros(UUID rcaRunId,
            Map<UUID, List<EvalQueryReader.UsageCallRow>> usageByRcaRun) {
        if (rcaRunId == null) {
            return null;
        }
        List<EvalQueryReader.UsageCallRow> rows = usageByRcaRun.get(rcaRunId);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        long sum = 0;
        String currency = null;
        for (EvalQueryReader.UsageCallRow row : rows) {
            if (row.usageMissing() || row.costMicros() == null) {
                return null;
            }
            if (row.currency() != null) {
                if (currency == null) {
                    currency = row.currency();
                } else if (!currency.equals(row.currency())) {
                    return null;
                }
            }
            sum += row.costMicros();
        }
        return sum;
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

    /** GET 面的同对最新落档引用（无落档如实 null；v1 历史行 ruleVersion 原样携带，
     *  前端据此标"历史规则"，不改写旧审计） */
    public GateRecordRef latestGateRecord(UUID baselineRunId, UUID candidateRunId) {
        return comparisons.findLatestByPair(baselineRunId, candidateRunId)
                .map(r -> new GateRecordRef(r.id(), r.gateOutcome(), r.gateRuleVersion(),
                        r.createdAt()))
                .orElse(null);
    }
}
