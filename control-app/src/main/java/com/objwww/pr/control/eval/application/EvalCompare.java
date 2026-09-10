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
import java.util.TreeMap;
import java.util.UUID;

/**
 * EV-07 配对工作台的纯函数段（EvalLogCompare 同模式，假输入可测；方案 §3.5/EV-07 卡）：
 * 可比性检查 → 案例配对 → 改善/退化/持平分类与判定变化矩阵 → 簇统计（PairedTrialStats
 * 簇级 bootstrap 复用）→ 对比质量门（冻结规则版本 {@value #GATE_RULE_VERSION}）。
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

    /** 对比质量门规则版本（冻结；阈值/分支序变更必须升版） */
    static final String GATE_RULE_VERSION = "eval-compare-gate-v1";
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

    // ------------------------------------------------------------------ 对比质量门（eval-compare-gate-v1）

    /** 门结论（ruleVersion + outcome + 机器码原因；EvaluationRecordV1 解释完整性同律） */
    record GateResult(String ruleVersion, String outcome, List<String> reasons) {
    }

    /**
     * 对比质量门（冻结分支序；QualityGate 五分支的对比面同构）：
     * <ol>
     *   <li>可比性未过 → NOT_EVALUABLE（不出配对结论）；</li>
     *   <li>读面扫描闸截断 → INCONCLUSIVE（部分数据不出资格结论）；</li>
     *   <li>无配对案例 → INCONCLUSIVE；</li>
     *   <li>独立簇不足 → INCONCLUSIVE（EU24：不伪造显著性）；</li>
     *   <li>退化率 &gt; {@value #MAX_REGRESSION_RATE} → FAIL；</li>
     *   <li>配对差值 CI 下界 &lt; -{@value #CI_MARGIN} → FAIL；</li>
     *   <li>全部通过 → PASS。</li>
     * </ol>
     */
    static GateResult gate(boolean comparable, boolean scanTruncated, int pairedCount,
                           int regressedCount, PairedTrialStats.StatsResult stats) {
        if (!comparable) {
            return new GateResult(GATE_RULE_VERSION,
                    EvalComparisonRecord.OUTCOME_NOT_EVALUABLE,
                    List.of(REASON_COMPARABILITY_CHECK_FAILED));
        }
        if (scanTruncated) {
            return new GateResult(GATE_RULE_VERSION,
                    EvalComparisonRecord.OUTCOME_INCONCLUSIVE, List.of(REASON_DATA_TRUNCATED));
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
