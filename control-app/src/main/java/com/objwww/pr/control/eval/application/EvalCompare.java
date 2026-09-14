package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.model.EvalComparisonRecord;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CompareCaseRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CompareRunMeta;
import com.objwww.pr.control.eval.domain.service.PairedTrialStats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * EV-07 配对工作台的纯函数段（EvalLogCompare 同模式，假输入可测；方案 §3.5/EV-07 卡）：
 * 可比性检查 → 案例配对 → 改善/退化/持平分类与判定变化矩阵 → 簇统计（PairedTrialStats
 * 簇级 bootstrap 复用）→ 对比质量门（冻结规则版本 {@value #GATE_RULE_VERSION}）。
 *
 * <p>FUP-02（v2）：EvidenceReadiness 输入区分"能展示差异"与"能产生最终结论"——
 * 运行未终态/不完整终态、无冻结计划分母、计划案例缺失、输入身份未核验一律
 * INCONCLUSIVE（暂态分析可展示，最终门不出 PASS）。v1 历史落档保留原结论与
 * 规则版本，不回读改写。
 *
 * <p>纪律：
 * <ul>
 *   <li><b>配对身份</b>：(scenarioId, roundNo) 精确键（uq_eval_case_result 保证 run 内
 *       唯一）；只统计两侧都有结果且输入 digest 未冲突的案例；单侧缺席/digest 冲突
 *       如实列入 unpaired（EU25），不猜归属；</li>
 *   <li><b>可比性</b>：严格维度（数据集版本/输入快照 registry_digest/规则版本
 *       alert_rule_digest/同义词典/场景驱动/评分对象选择策略集）全等才 comparable；
 *       不一致出差异清单且不出配对结论（前端只展示）。grader 版本无列 → 维度
 *       equal=null 如实 NOT_AVAILABLE（偏差不冒充）；model/promptVersion 为信息面
 *       （模型差异正是对比动机，不参与严格判定）；</li>
 *   <li><b>诚实边界</b>：扫描闸截断 → 门 INCONCLUSIVE(DATA_TRUNCATED)；独立簇不足 →
 *       统计 INCONCLUSIVE 且门不出 PASS（EU24 不伪造显著性）；分母 0 的三件套
 *       NOT_APPLICABLE 装配归服务层。</li>
 * </ul>
 */
final class EvalCompare {

    /** 对比质量门规则版本（冻结；阈值/分支序变更必须升版）。
     *  FUP-02：v1 → v2 引入 EvidenceReadiness 前置分支（运行终态/冻结计划覆盖/
     *  身份核验），v1 历史落档不改写 */
    static final String GATE_RULE_VERSION = "eval-compare-gate-v2";
    /** v1 历史规则版本（FUP-02 前落档行；只读比对用，不再产出） */
    static final String GATE_RULE_VERSION_V1 = "eval-compare-gate-v1";
    /** 退化率阈值（regressed/paired 超过即 FAIL；方案 §3.5 "退化率超阈值" 面） */
    static final double MAX_REGRESSION_RATE = 0.10;
    /** 配对差值 CI 下界余量（ciLower &lt; -margin → FAIL；QualityGate 分支3 同构） */
    static final double CI_MARGIN = 0.05;

    // 门机器码原因词表（冻结）
    static final String REASON_COMPARABILITY_CHECK_FAILED = "COMPARABILITY_CHECK_FAILED";
    static final String REASON_DATA_TRUNCATED = "DATA_TRUNCATED";
    static final String REASON_NO_PAIRED_CASES = "NO_PAIRED_CASES";
    static final String REASON_INSUFFICIENT_CLUSTERS = "INSUFFICIENT_CLUSTERS";
    static final String REASON_REGRESSION_RATE_EXCEEDED = "REGRESSION_RATE_EXCEEDED";
    static final String REASON_CI_LOWER_BELOW_MARGIN = "CI_LOWER_BELOW_MARGIN";
    // FUP-02 v2 新增：证据就绪度分支机器码
    static final String REASON_RUN_NOT_FINAL = "RUN_NOT_FINAL";
    static final String REASON_RUN_INCOMPLETE = "RUN_INCOMPLETE";
    static final String REASON_PLAN_SET_UNAVAILABLE = "PLAN_SET_UNAVAILABLE";
    static final String REASON_PLAN_CASES_MISSING = "PLAN_CASES_MISSING";
    static final String REASON_IDENTITY_UNVERIFIED = "IDENTITY_UNVERIFIED";

    /** 计划分母来源词表（FUP-02）：冻结计划键集 / 无快照降级 */
    static final String PLAN_SOURCE_LAUNCH_PLAN = "LAUNCH_PLAN";
    static final String PLAN_SOURCE_UNAVAILABLE = "UNAVAILABLE";

    /** 运行终态性（FUP-02）：SUCCEEDED = 完整终态；FAILED/CANCELLED = 不完整终态；
     *  其余（RUNNING/PENDING/未知值）= 未终态 */
    private static final String FINALITY_FINAL = "FINAL";
    private static final String FINALITY_NOT_FINAL = "NOT_FINAL";
    private static final String FINALITY_TERMINAL_INCOMPLETE = "TERMINAL_INCOMPLETE";

    /** 分组词表（前端改善/退化/持平三区） */
    static final String GROUP_IMPROVED = "IMPROVED";
    static final String GROUP_REGRESSED = "REGRESSED";
    static final String GROUP_FLAT = "FLAT";

    private EvalCompare() {
    }

    // ------------------------------------------------------------------ 可比性

    /** 可比性维度（strict=true 参与 comparable 判定；equal=null = 无数据源如实不可判） */
    record Dimension(String name, String baseline, String candidate, Boolean equal,
                     boolean strict) {
    }

    /** 可比性结果：维度清单（含信息面）+ 严格面全等结论 + 不一致严格维度名清单 */
    record Comparability(List<Dimension> dimensions, boolean comparable,
                         List<String> mismatches) {
    }

    /**
     * 可比性检查（§3.5 顶部检查带）。严格维度全等 → comparable=true；
     * grader 版本无列 → equal=null 的 NOT_AVAILABLE 维度如实列出但不参与判定。
     *
     * @param baselinePolicyVersions 基线 run 逐案例 selection_policy_version 去重集
     *                               （评分器语义锚；升序拼接为维度值）
     */
    static Comparability comparability(CompareRunMeta baseline, CompareRunMeta candidate,
                                       List<String> baselinePolicyVersions,
                                       List<String> candidatePolicyVersions) {
        List<Dimension> dims = new ArrayList<>(9);
        dims.add(dim("datasetVersion", baseline.datasetVersion(), candidate.datasetVersion(), true));
        dims.add(dim("inputSnapshotDigest", baseline.registryDigest(),
                candidate.registryDigest(), true));
        dims.add(dim("rulesVersionDigest", baseline.alertRuleDigest(),
                candidate.alertRuleDigest(), true));
        dims.add(dim("lexiconVersion", str(baseline.lexiconVersion()),
                str(candidate.lexiconVersion()), true));
        dims.add(dim("scenarioDriverVersion", baseline.scenarioDriverVersion(),
                candidate.scenarioDriverVersion(), true));
        dims.add(dim("selectionPolicyVersions", String.join(",", baselinePolicyVersions),
                String.join(",", candidatePolicyVersions), true));
        // grader 版本无列（偏差如实：不冒充不阻断）
        dims.add(new Dimension("graderVersion", null, null, null, false));
        // 信息面：模型/Prompt/整体配置差异是对比动机本身，不参与严格判定
        dims.add(dim("model", baseline.model(), candidate.model(), false));
        dims.add(dim("promptVersion", baseline.promptVersion(), candidate.promptVersion(), false));
        dims.add(dim("configDigest", baseline.configDigest(), candidate.configDigest(), false));
        List<String> mismatches = new ArrayList<>();
        for (Dimension d : dims) {
            if (d.strict() && !Boolean.TRUE.equals(d.equal())) {
                mismatches.add(d.name());
            }
        }
        return new Comparability(List.copyOf(dims), mismatches.isEmpty(),
                List.copyOf(mismatches));
    }

    private static Dimension dim(String name, String baseline, String candidate, boolean strict) {
        return new Dimension(name, baseline, candidate,
                Objects.equals(baseline, candidate), strict);
    }

    private static String str(Integer value) {
        return value == null ? null : String.valueOf(value);
    }

    // ------------------------------------------------------------------ 配对

    /** 单侧案例摘要（差异说明与逐例表的输入） */
    record PairedCase(String scenarioId, int roundNo, String group,
                      CompareCaseRow baseline, CompareCaseRow candidate,
                      String verdictChange, String differenceNote,
                      String inputDigestMatch, String clusterId, String faultType) {
    }

    /** 未配对案例（side ∈ BASELINE_ONLY / CANDIDATE_ONLY；reason 含输入冲突面） */
    record UnpairedCase(String scenarioId, int roundNo, String side, String reason,
                        UUID caseExecutionId) {
    }

    /** 簇统计桶（按冻结场景族/场景回退键；pointEstimate = 簇内对差值均值） */
    record ClusterStat(String clusterId, int paired, int improved, int regressed, int flat,
                       double pointEstimate) {
    }

    /** 故障源统计桶（expected_root_cause.fault_type 分桶；UNSPECIFIED = 无故障源字段） */
    record FaultTypeStat(String faultType, int paired, int improved, int regressed, int flat) {
    }

    /** 配对结果全集（配对表/未配对表/判定变化矩阵/簇统计/故障源分桶/配对统计） */
    record Pairing(List<PairedCase> pairs, List<UnpairedCase> unpaired,
                   Map<String, Integer> verdictChangeMatrix,
                   List<ClusterStat> clusters, List<FaultTypeStat> byFaultType,
                   PairedTrialStats.StatsResult stats) {
    }

    /**
     * 按 (scenarioId, roundNo) 精确键配对两 run 案例（输入须已按同键升序）。
     * 两侧都有结果且 digest 不冲突 → paired；digest 两侧皆可解析且不等 →
     * unpaired(INPUT_DIGEST_MISMATCH)；单侧缺席 → unpaired(BASELINE_ONLY/CANDIDATE_ONLY)。
     * digest 任侧不可解析（无匹配/歧义/HOLDOUT）→ 不阻断配对，inputDigestMatch=UNVERIFIED。
     */
    static Pairing pair(ObjectMapper mapper, List<CompareCaseRow> baseline,
                        List<CompareCaseRow> candidate, long statsSeed) {
        Map<String, CompareCaseRow> baselineByKey = byKey(baseline);
        Map<String, CompareCaseRow> candidateByKey = byKey(candidate);
        Map<String, Boolean> allKeys = new TreeMap<>();
        baselineByKey.keySet().forEach(k -> allKeys.put(k, Boolean.TRUE));
        candidateByKey.keySet().forEach(k -> allKeys.put(k, Boolean.TRUE));

        List<PairedCase> pairs = new ArrayList<>();
        List<UnpairedCase> unpaired = new ArrayList<>();
        for (String key : allKeys.keySet()) {
            CompareCaseRow b = baselineByKey.get(key);
            CompareCaseRow c = candidateByKey.get(key);
            if (b == null) {
                unpaired.add(new UnpairedCase(c.scenarioId(), c.roundNo(),
                        "CANDIDATE_ONLY", "MISSING_IN_BASELINE", c.caseExecutionId()));
            } else if (c == null) {
                unpaired.add(new UnpairedCase(b.scenarioId(), b.roundNo(),
                        "BASELINE_ONLY", "MISSING_IN_CANDIDATE", b.caseExecutionId()));
            } else if (b.contentDigest() != null && c.contentDigest() != null
                    && !b.contentDigest().equals(c.contentDigest())) {
                // EU25：输入不同不冒充配对——如实列入 unpaired（两侧各记一条缺席语义）
                unpaired.add(new UnpairedCase(b.scenarioId(), b.roundNo(),
                        "BASELINE_ONLY", "INPUT_DIGEST_MISMATCH", b.caseExecutionId()));
                unpaired.add(new UnpairedCase(c.scenarioId(), c.roundNo(),
                        "CANDIDATE_ONLY", "INPUT_DIGEST_MISMATCH", c.caseExecutionId()));
            } else {
                pairs.add(pairedCase(mapper, b, c));
            }
        }

        Map<String, Integer> matrix = new TreeMap<>();
        Map<String, List<PairedCase>> byCluster = new LinkedHashMap<>();
        Map<String, int[]> byFault = new TreeMap<>();
        List<PairedTrialStats.PairedOutcome> outcomes = new ArrayList<>(pairs.size());
        for (PairedCase p : pairs) {
            matrix.merge(p.verdictChange(), 1, Integer::sum);
            byCluster.computeIfAbsent(p.clusterId(), k -> new ArrayList<>()).add(p);
            int[] bucket = byFault.computeIfAbsent(p.faultType(), k -> new int[4]);
            accumulate(bucket, p.group());
            outcomes.add(new PairedTrialStats.PairedOutcome(p.clusterId(),
                    p.baseline().rootCauseHit(), p.candidate().rootCauseHit()));
        }
        List<ClusterStat> clusters = new ArrayList<>(byCluster.size());
        for (Map.Entry<String, List<PairedCase>> e : byCluster.entrySet()) {
            int[] counts = new int[4];
            double diffSum = 0;
            for (PairedCase p : e.getValue()) {
                accumulate(counts, p.group());
                diffSum += (p.candidate().rootCauseHit() ? 1 : 0)
                        - (p.baseline().rootCauseHit() ? 1 : 0);
            }
            clusters.add(new ClusterStat(e.getKey(), e.getValue().size(),
                    counts[1], counts[2], counts[3], diffSum / e.getValue().size()));
        }
        List<FaultTypeStat> faultStats = new ArrayList<>(byFault.size());
        for (Map.Entry<String, int[]> e : byFault.entrySet()) {
            int[] counts = e.getValue();
            faultStats.add(new FaultTypeStat(e.getKey(), counts[0],
                    counts[1], counts[2], counts[3]));
        }
        PairedTrialStats.StatsResult stats = outcomes.isEmpty() ? null
                : PairedTrialStats.pairedDifference(outcomes, statsSeed);
        return new Pairing(List.copyOf(pairs), List.copyOf(unpaired),
                Map.copyOf(matrix), List.copyOf(clusters), List.copyOf(faultStats), stats);
    }

    /** counts = [paired, improved, regressed, flat] */
    private static void accumulate(int[] counts, String group) {
        counts[0]++;
        switch (group) {
            case GROUP_IMPROVED -> counts[1]++;
            case GROUP_REGRESSED -> counts[2]++;
            default -> counts[3]++;
        }
    }

    /** 单对分类：rootCauseHit 差值分组 + 判定变化 + 差异说明 + 簇键/故障源解析 */
    private static PairedCase pairedCase(ObjectMapper mapper, CompareCaseRow b,
                                         CompareCaseRow c) {
        String group = !b.rootCauseHit() && c.rootCauseHit() ? GROUP_IMPROVED
                : b.rootCauseHit() && !c.rootCauseHit() ? GROUP_REGRESSED : GROUP_FLAT;
        String verdictChange = b.verdict() + "→" + c.verdict();
        String note = differenceNote(group, b, c, verdictChange);
        String digestMatch = b.contentDigest() == null || c.contentDigest() == null
                ? "UNVERIFIED" : "MATCH";
        String clusterId = b.scenarioFamilyId() != null ? b.scenarioFamilyId()
                : c.scenarioFamilyId() != null ? c.scenarioFamilyId() : b.scenarioId();
        String faultType = faultType(mapper, b.expectedRootCauseJson());
        if (faultType == null) {
            faultType = faultType(mapper, c.expectedRootCauseJson());
        }
        return new PairedCase(b.scenarioId(), b.roundNo(), group, b, c, verdictChange, note,
                digestMatch, clusterId, faultType == null ? "UNSPECIFIED" : faultType);
    }

    /** 差异说明（人读一句；持平且判定同形 → null 不出噪声） */
    private static String differenceNote(String group, CompareCaseRow b, CompareCaseRow c,
                                         String verdictChange) {
        String hit = b.rootCauseHit() ? "命中" : "未命中";
        String other = c.rootCauseHit() ? "命中" : "未命中";
        return switch (group) {
            case GROUP_IMPROVED -> "根因" + hit + "→" + other + "（判定 " + verdictChange + "）";
            case GROUP_REGRESSED -> "根因" + hit + "→" + other + "（判定 " + verdictChange + "）";
            default -> b.verdict().equals(c.verdict()) ? null
                    : "命中面一致（判定 " + verdictChange + "）";
        };
    }

    /** expected_root_cause jsonb 的 fault_type 抽取（解析失败/缺字段如实 null） */
    private static String faultType(ObjectMapper mapper, String expectedJson) {
        if (expectedJson == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(expectedJson).path("fault_type");
            return node.isTextual() ? node.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, CompareCaseRow> byKey(List<CompareCaseRow> rows) {
        Map<String, CompareCaseRow> out = new TreeMap<>();
        for (CompareCaseRow row : rows) {
            // TreeMap 字典序对 (scenario, round) 复合序不稳——roundNo 补零对齐保证排序即配对序
            out.put(row.scenarioId() + "" + String.format("%06d", row.roundNo()), row);
        }
        return out;
    }

    // ------------------------------------------------------------------ FUP-02 证据就绪度（gate v2 输入）

    /** 配对/计划核算共用的键编码（与 byKey 同式：scenarioId + '\b' + 补零 roundNo） */
    private static String key(String scenarioId, int roundNo) {
        return scenarioId + "\b" + String.format("%06d", roundNo);
    }

    /** FUP-02 缺失案例行（reason 词表 = 未配对三路 + MISSING_IN_BOTH 双侧同缺） */
    record MissingCase(String scenarioId, int roundNo, String reason) {
    }

    static final String MISSING_IN_BOTH = "MISSING_IN_BOTH";

    /**
     * FUP-02 冻结计划面（服务层从 eval_run.launch_plan 快照 + 数据集案例键集装配）：
     * caseKeys = 数据集版本下可见 case_version.case_key 集；baselineRounds/
     * candidateRounds = 各侧 roundsPerScenario。null 整体 = 无冻结计划快照降级
     * （旧 CLI 跑批 launch_plan 为 NULL 等历史面），门按 PLAN_SET_UNAVAILABLE 处理。
     */
    record FrozenPlan(Set<String> caseKeys, int baselineRounds,
                      int candidateRounds) {
    }

    /**
     * FUP-02 证据就绪度（区分"能展示差异"与"能产生最终结论"）：
     * 运行状态原样携带；expected/completed/missing/unexpected 按计划键集
     * （caseKey × roundNo）核算——计划源不可用（UNAVAILABLE）时四项如实 null
     * （不拿双侧并集冒充计划分母：两侧一起漏同一案例时并集也缺）。
     * verified = 配对中输入 digest MATCH 数；UNVERIFIED 可展示差异但不计身份已核验。
     */
    record EvidenceReadiness(String baselineState, String candidateState,
                             String planSetSource, Integer expectedCount,
                             Integer completedCount, int pairedCount, int verifiedCount,
                             int unverifiedCount, Integer missingCount,
                             Integer unexpectedCount, List<MissingCase> missingCases) {
    }

    /**
     * 就绪度核算（纯函数）：配对/未配对结果 + 冻结计划键集 → expected/completed/
     * paired/verified/missing/unexpected 与缺失清单（按 (scenarioId, roundNo) 升序，
     * 截断归服务层）。计划键未有效配对的三路归属：单侧缺席沿用未配对原因、
     * digest 冲突沿用 INPUT_DIGEST_MISMATCH、双侧同缺 MISSING_IN_BOTH。
     */
    static EvidenceReadiness readiness(CompareRunMeta baseline, CompareRunMeta candidate,
                                       List<CompareCaseRow> baselineCases,
                                       List<CompareCaseRow> candidateCases,
                                       Pairing pairing, FrozenPlan plan) {
        int verified = 0;
        int unverified = 0;
        Map<String, PairedCase> pairedByKey = new TreeMap<>();
        for (PairedCase p : pairing.pairs()) {
            pairedByKey.put(key(p.scenarioId(), p.roundNo()), p);
            if ("MATCH".equals(p.inputDigestMatch())) {
                verified++;
            } else {
                unverified++;
            }
        }
        if (plan == null) {
            return new EvidenceReadiness(baseline.state(), candidate.state(),
                    PLAN_SOURCE_UNAVAILABLE, null, null, pairing.pairs().size(),
                    verified, unverified, null, null, List.of());
        }
        // 期望键集：计划案例键 × 轮次（两侧轮次不同取并集 = 较大轮次全覆盖）
        Map<String, MissingCase> expectedParts = new TreeMap<>();
        int rounds = Math.max(plan.baselineRounds(), plan.candidateRounds());
        for (String caseKey : plan.caseKeys()) {
            for (int r = 1; r <= rounds; r++) {
                expectedParts.put(key(caseKey, r), new MissingCase(caseKey, r, null));
            }
        }
        Map<String, CompareCaseRow> baselineByKey = byKey(baselineCases);
        Map<String, CompareCaseRow> candidateByKey = byKey(candidateCases);
        Map<String, String> unpairedReason = new TreeMap<>();
        for (UnpairedCase u : pairing.unpaired()) {
            // digest 冲突出双行（各侧一条）：合并时 INPUT_DIGEST_MISMATCH 优先
            unpairedReason.merge(key(u.scenarioId(), u.roundNo()), u.reason(),
                    (a, b) -> "INPUT_DIGEST_MISMATCH".equals(a) ? a : b);
        }
        int completed = 0;
        List<MissingCase> missing = new ArrayList<>();
        for (Map.Entry<String, MissingCase> e : expectedParts.entrySet()) {
            String k = e.getKey();
            if (baselineByKey.containsKey(k) && candidateByKey.containsKey(k)) {
                completed++;
            }
            if (pairedByKey.containsKey(k)) {
                continue;
            }
            MissingCase parts = e.getValue();
            missing.add(new MissingCase(parts.scenarioId(), parts.roundNo(),
                    unpairedReason.getOrDefault(k, MISSING_IN_BOTH)));
        }
        int unexpected = 0;
        Map<String, Boolean> observed = new TreeMap<>();
        baselineByKey.keySet().forEach(k -> observed.put(k, Boolean.TRUE));
        candidateByKey.keySet().forEach(k -> observed.put(k, Boolean.TRUE));
        for (String k : observed.keySet()) {
            if (!expectedParts.containsKey(k)) {
                unexpected++;
            }
        }
        return new EvidenceReadiness(baseline.state(), candidate.state(),
                PLAN_SOURCE_LAUNCH_PLAN, expectedParts.size(), completed,
                pairing.pairs().size(), verified, unverified, missing.size(), unexpected,
                List.copyOf(missing));
    }

    /** 运行终态性分类（FUP-02；词表外未知值一律按未终态处理，不猜） */
    private static String finality(String state) {
        if ("SUCCEEDED".equals(state)) {
            return FINALITY_FINAL;
        }
        if ("FAILED".equals(state) || "CANCELLED".equals(state)) {
            return FINALITY_TERMINAL_INCOMPLETE;
        }
        return FINALITY_NOT_FINAL;
    }

    // ------------------------------------------------------------------ 对比质量门（eval-compare-gate-v2）

    /** 门结论（ruleVersion + outcome + 机器码原因；EvaluationRecordV1 解释完整性同律） */
    record GateResult(String ruleVersion, String outcome, List<String> reasons) {
    }

    /**
     * 对比质量门（冻结分支序；QualityGate 五分支的对比面同构）。
     * FUP-02 v2：在统计判定之前插入证据就绪度前置分支——
     * <ol>
     *   <li>可比性未过 → NOT_EVALUABLE（不出配对结论）；</li>
     *   <li>任一 run 未终态（RUNNING/PENDING/未知值）→ INCONCLUSIVE(RUN_NOT_FINAL)
     *       ——暂态分析可展示，最终门不出结论；</li>
     *   <li>任一 run 不完整终态（FAILED/CANCELLED）→ INCONCLUSIVE(RUN_INCOMPLETE)
     *       ——局部命中率再好也不成最终 PASS；</li>
     *   <li>读面扫描闸截断 → INCONCLUSIVE（部分数据不出资格结论）；</li>
     *   <li>无冻结计划分母（历史 run 缺 launch_plan 快照）→ INCONCLUSIVE
     *       (PLAN_SET_UNAVAILABLE)——明确降级，不拿双侧并集冒充计划完整；</li>
     *   <li>计划键未全部有效配对（含单侧缺席/digest 冲突/双侧同缺）→ INCONCLUSIVE
     *       (PLAN_CASES_MISSING)——应配对全部配对才有最终门；</li>
     *   <li>配对中存在输入身份未核验（digest UNVERIFIED）→ INCONCLUSIVE
     *       (IDENTITY_UNVERIFIED)——可展示差异，不算身份已核验；</li>
     *   <li>无配对案例 → INCONCLUSIVE；</li>
     *   <li>独立簇不足 → INCONCLUSIVE（EU24：不伪造显著性）；</li>
     *   <li>退化率 &gt; {@value #MAX_REGRESSION_RATE} → FAIL；</li>
     *   <li>配对差值 CI 下界 &lt; -{@value #CI_MARGIN} → FAIL；</li>
     *   <li>全部通过 → PASS。</li>
     * </ol>
     * readiness 仅分支 2~7 消费；分支 1 短路时服务层传 null。
     */
    static GateResult gate(boolean comparable, boolean scanTruncated,
                           EvidenceReadiness readiness, int pairedCount,
                           int regressedCount, PairedTrialStats.StatsResult stats) {
        if (!comparable) {
            return new GateResult(GATE_RULE_VERSION,
                    EvalComparisonRecord.OUTCOME_NOT_EVALUABLE,
                    List.of(REASON_COMPARABILITY_CHECK_FAILED));
        }
        Objects.requireNonNull(readiness, "comparable 面 readiness 不得为 null");
        String baselineFinality = finality(readiness.baselineState());
        String candidateFinality = finality(readiness.candidateState());
        if (FINALITY_NOT_FINAL.equals(baselineFinality)
                || FINALITY_NOT_FINAL.equals(candidateFinality)) {
            return new GateResult(GATE_RULE_VERSION,
                    EvalComparisonRecord.OUTCOME_INCONCLUSIVE, List.of(REASON_RUN_NOT_FINAL));
        }
        if (FINALITY_TERMINAL_INCOMPLETE.equals(baselineFinality)
                || FINALITY_TERMINAL_INCOMPLETE.equals(candidateFinality)) {
            return new GateResult(GATE_RULE_VERSION,
                    EvalComparisonRecord.OUTCOME_INCONCLUSIVE, List.of(REASON_RUN_INCOMPLETE));
        }
        if (scanTruncated) {
            return new GateResult(GATE_RULE_VERSION,
                    EvalComparisonRecord.OUTCOME_INCONCLUSIVE, List.of(REASON_DATA_TRUNCATED));
        }
        if (!PLAN_SOURCE_LAUNCH_PLAN.equals(readiness.planSetSource())) {
            return new GateResult(GATE_RULE_VERSION,
                    EvalComparisonRecord.OUTCOME_INCONCLUSIVE,
                    List.of(REASON_PLAN_SET_UNAVAILABLE));
        }
        if (readiness.missingCount() != null && readiness.missingCount() > 0) {
            return new GateResult(GATE_RULE_VERSION,
                    EvalComparisonRecord.OUTCOME_INCONCLUSIVE,
                    List.of(REASON_PLAN_CASES_MISSING));
        }
        if (readiness.unverifiedCount() > 0) {
            return new GateResult(GATE_RULE_VERSION,
                    EvalComparisonRecord.OUTCOME_INCONCLUSIVE,
                    List.of(REASON_IDENTITY_UNVERIFIED));
        }
        if (pairedCount == 0) {
            return new GateResult(GATE_RULE_VERSION,
                    EvalComparisonRecord.OUTCOME_INCONCLUSIVE, List.of(REASON_NO_PAIRED_CASES));
        }
        if (stats == null || stats.verdict() == PairedTrialStats.Verdict.INCONCLUSIVE
                || stats.clusterCount() < PairedTrialStats.MIN_CLUSTERS) {
            return new GateResult(GATE_RULE_VERSION,
                    EvalComparisonRecord.OUTCOME_INCONCLUSIVE, List.of(REASON_INSUFFICIENT_CLUSTERS));
        }
        double regressionRate = (double) regressedCount / pairedCount;
        if (regressionRate > MAX_REGRESSION_RATE) {
            return new GateResult(GATE_RULE_VERSION,
                    EvalComparisonRecord.OUTCOME_FAIL, List.of(REASON_REGRESSION_RATE_EXCEEDED));
        }
        if (stats.ciLower() < -CI_MARGIN) {
            return new GateResult(GATE_RULE_VERSION,
                    EvalComparisonRecord.OUTCOME_FAIL, List.of(REASON_CI_LOWER_BELOW_MARGIN));
        }
        return new GateResult(GATE_RULE_VERSION, EvalComparisonRecord.OUTCOME_PASS, List.of());
    }

    /**
     * 统计种子（冻结推导：同有序对 (baseline, candidate) 重算必得同区间——方向敏感，
     * 交换基线/候选即换种子，与差值方向一致性同律；可复现锚随落档记录）。
     */
    static long statsSeed(UUID baselineRunId, UUID candidateRunId) {
        long h = baselineRunId.getMostSignificantBits();
        h = h * 31 + baselineRunId.getLeastSignificantBits();
        h = h * 31 + candidateRunId.getMostSignificantBits();
        h = h * 31 + candidateRunId.getLeastSignificantBits();
        return h;
    }
}
