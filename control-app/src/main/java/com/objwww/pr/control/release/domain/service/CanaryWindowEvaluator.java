package com.objwww.pr.control.release.domain.service;

import com.objwww.pr.control.release.domain.model.CanaryEvidenceClass;
import com.objwww.pr.control.release.domain.model.CanaryWindowPolicy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Canary 窗口评判器（M6-01，方案 §3.3）：按窗身份（四 digest + 比例带 + 分级）
 * 把采集样本聚成单窗草案（Draft）——incident 按 stickinessKey 聚类计独立样本、
 * 失败聚合取保守（任一失败即事件失败）、分层计数、双门（绝对 SLO + 相对 control
 * 容差）、critical 否决；样本不足/无对照 → INCONCLUSIVE（不评判不落 FAIL）。
 * 策略值全部来自 bundle（{@link CanaryWindowPolicy}，缺段 fail-closed O-63）。
 * 零框架：jsonb 载荷以 Map 承载，序列化归基础设施。
 *
 * @author wanghua
 * @date 2026-09-08
 */
public class CanaryWindowEvaluator {

    private static final String VERDICT_PASS = "PASS";
    private static final String VERDICT_FAIL = "FAIL";
    private static final String VERDICT_INCONCLUSIVE = "INCONCLUSIVE";

    /** 单窗身份：rolloutId + 三 digest + 比例带 + 窗序号 + 分级 + 窗口墙钟（V30 uq_cwv_window 同域） */
    public record WindowIdentity(UUID rolloutId,
                                 String candidateDigest,
                                 String rolloutPolicyDigest,
                                 String capabilityDigest,
                                 int fromPercent,
                                 int toPercent,
                                 int windowSeq,
                                 CanaryEvidenceClass evidenceClass,
                                 Instant windowStart,
                                 Instant windowEnd) {
    }

    /** 单条采集样本：stickinessKey 聚类轴；tenant/severity 入分层 */
    public record Sample(String stickinessKey,
                         String tenant,
                         String severity,
                         boolean failed,
                         Instant observedAt) {
    }

    /** 窗草案：判定四明细 + 结论；rawCounts/strata/control/absoluteSlo 原样入 provenance/observed jsonb */
    public record Draft(WindowIdentity identity,
                        int eligibleIncidents,
                        Map<String, Object> rawCounts,
                        Map<String, Object> strata,
                        Map<String, Object> control,
                        Map<String, Object> absoluteSlo,
                        Boolean criticalPass,
                        String verdict) {
    }

    /**
     * 评判单窗。样本按 stickinessKey 聚类：独立事件数 = 不同 key 数；事件失败 =
     * 该 key 下任一样本失败（保守）。eligible &lt; minSamples → INCONCLUSIVE
     * (INSUFFICIENT_SAMPLES)；无对照 → INCONCLUSIVE (NO_CONTROL_COHORT)；
     * critical 明确 FALSE → FAIL（安全门一票否决）；绝对门或相对门越界 → FAIL。
     */
    public Draft evaluate(WindowIdentity identity,
                          List<Sample> nativeSamples,
                          List<Sample> controlSamples,
                          Boolean criticalPass,
                          CanaryWindowPolicy policy) {
        Map<String, List<Sample>> clusters = new LinkedHashMap<>();
        for (Sample sample : nativeSamples) {
            clusters.computeIfAbsent(sample.stickinessKey(), k -> new ArrayList<>()).add(sample);
        }
        int eligible = clusters.size();
        long failedIncidents = clusters.values().stream().filter(this::anyFailed).count();

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("native_total", nativeSamples.size());
        raw.put("eligible_incidents", eligible);
        raw.put("failed_incidents", (int) failedIncidents);
        raw.put("critical_pass", criticalPass);

        Map<String, Object> strata = strataOf(nativeSamples);

        if (controlSamples.isEmpty()) {
            raw.put("excluded_reason", "NO_CONTROL_COHORT");
            return new Draft(identity, eligible, raw, strata, Map.of(), Map.of(), criticalPass,
                    VERDICT_INCONCLUSIVE);
        }
        if (eligible < policy.minSamplesPerWindow()) {
            raw.put("excluded_reason", "INSUFFICIENT_SAMPLES");
            return new Draft(identity, eligible, raw, strata, Map.of(), Map.of(), criticalPass,
                    VERDICT_INCONCLUSIVE);
        }

        double nativeRate = (double) failedIncidents / eligible;
        long controlFailed = controlSamples.stream().filter(Sample::failed).count();
        double controlRate = (double) controlFailed / controlSamples.size();
        raw.put("control_total", controlSamples.size());
        raw.put("control_failed", (int) controlFailed);
        raw.put("native_fail_rate", nativeRate);

        Map<String, Object> controlGate = new LinkedHashMap<>();
        controlGate.put("native_fail_rate", nativeRate);
        controlGate.put("control_fail_rate", controlRate);
        controlGate.put("relative_tolerance", policy.relativeTolerance());
        boolean controlPass = nativeRate <= controlRate + policy.relativeTolerance();
        controlGate.put("gate", controlPass ? VERDICT_PASS : VERDICT_FAIL);

        Map<String, Object> sloGate = new LinkedHashMap<>();
        sloGate.put("native_fail_rate", nativeRate);
        sloGate.put("max_absolute_fail_rate", policy.maxAbsoluteFailRate());
        boolean sloPass = nativeRate <= policy.maxAbsoluteFailRate();
        sloGate.put("gate", sloPass ? VERDICT_PASS : VERDICT_FAIL);

        String verdict;
        if (Boolean.FALSE.equals(criticalPass)) {
            verdict = VERDICT_FAIL;
        } else if (!sloPass || !controlPass) {
            verdict = VERDICT_FAIL;
        } else {
            verdict = VERDICT_PASS;
        }
        return new Draft(identity, eligible, raw, strata, controlGate, sloGate, criticalPass, verdict);
    }

    /**
     * 连续 K 窗判定（晋升资格）：仅 LIVE_CANARY 且 PASS 的窗计入（INV-AM6-5：
     * DRILL/REPLAY 全优不晋升）；同一窗身份（三 digest + 比例带，digest 变化即
     * 作废矩阵）内 windowSeq 逐窗 +1 连续才算链——非 LIVE 窗占槽自然断链。
     */
    public static boolean hasConsecutivePasses(List<Draft> drafts, int k) {
        Map<String, List<Integer>> byIdentity = new HashMap<>();
        for (Draft draft : drafts) {
            WindowIdentity id = draft.identity();
            if (id.evidenceClass() != CanaryEvidenceClass.LIVE_CANARY
                    || !VERDICT_PASS.equals(draft.verdict())) {
                continue;
            }
            byIdentity.computeIfAbsent(identityKey(id), key -> new ArrayList<>())
                    .add(id.windowSeq());
        }
        for (List<Integer> seqs : byIdentity.values()) {
            seqs.sort(Comparator.naturalOrder());
            int run = 1;
            int best = 1;
            for (int i = 1; i < seqs.size(); i++) {
                run = seqs.get(i) == seqs.get(i - 1) + 1 ? run + 1 : 1;
                best = Math.max(best, run);
            }
            if (best >= k) {
                return true;
            }
        }
        return false;
    }

    /** 身份键：三 digest + 比例带（rolloutId 为实例轴不入键；seq 为排序轴不入键） */
    private static String identityKey(WindowIdentity id) {
        return id.candidateDigest() + "|" + id.rolloutPolicyDigest() + "|" + id.capabilityDigest()
                + "|" + id.fromPercent() + "|" + id.toPercent();
    }

    private boolean anyFailed(List<Sample> cluster) {
        return cluster.stream().anyMatch(Sample::failed);
    }

    /** 分层计数（tenant.X / severity.Y 前缀，样本粒度） */
    private Map<String, Object> strataOf(List<Sample> samples) {
        Map<String, Object> strata = new LinkedHashMap<>();
        Map<String, Integer> tenants = new LinkedHashMap<>();
        Map<String, Integer> severities = new LinkedHashMap<>();
        for (Sample sample : samples) {
            tenants.merge("tenant." + sample.tenant(), 1, Integer::sum);
            severities.merge("severity." + sample.severity(), 1, Integer::sum);
        }
        strata.putAll(tenants);
        strata.putAll(severities);
        return strata;
    }
}
