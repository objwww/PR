package com.objwww.pr.control.release.domain.service;

import com.objwww.pr.control.release.domain.model.CanaryEvidenceClass;
import com.objwww.pr.control.release.domain.model.CanaryWindowPolicy;
import com.objwww.pr.control.release.domain.service.CanaryWindowEvaluator.Draft;
import com.objwww.pr.control.release.domain.service.CanaryWindowEvaluator.Sample;
import com.objwww.pr.control.release.domain.service.CanaryWindowEvaluator.WindowIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CanaryWindowEvaluator UT（M6-01 落点 8；方案 §3.3/UT 清单）：四 digest 建窗身份、
 * incident 聚类（stickiness 去重计独立样本）、缺数 INCONCLUSIVE、双门（相对 control
 * + 绝对 SLO）、critical 否决、连续 K 窗（仅 LIVE_CANARY 计入晋升，DRILL/REPLAY
 * 永不计入——INV-AM6-5）。策略值全部来自 bundle（缺段 fail-closed，禁硬编码 O-63）。
 */
class CanaryWindowEvaluatorTest {

    private static final Instant START = Instant.parse("2026-09-08T00:00:00Z");
    private static final Instant END = START.plus(Duration.ofHours(24));
    private static final CanaryWindowPolicy POLICY = new CanaryWindowPolicy(
            5, 3, Duration.ofHours(24), 0.20, 0.05);

    private final CanaryWindowEvaluator evaluator = new CanaryWindowEvaluator();

    // ------------------------------------------------------------------ 聚类与缺数

    @Test
    @DisplayName("incident 聚类：同 stickiness 多样本记一独立事件；失败聚合取保守（任一失败=事件失败）")
    void clustersSamplesByStickinessKey() {
        WindowIdentity id = identity(CanaryEvidenceClass.LIVE_CANARY, 1);
        List<Sample> nativeSamples = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            nativeSamples.add(new Sample("inc-a", "t1", "critical", false, START));
        }
        nativeSamples.add(new Sample("inc-a", "t1", "critical", true, START));
        nativeSamples.add(new Sample("inc-b", "t2", "warning", false, START));
        nativeSamples.add(new Sample("inc-b", "t2", "warning", false, START));
        nativeSamples.add(new Sample("inc-c", "t1", "warning", false, START));
        nativeSamples.add(new Sample("inc-d", "t3", "critical", false, START));
        nativeSamples.add(new Sample("inc-e", "t2", "critical", false, START));

        // 5 独立事件 = min_samples=5 边界；native 失败率 1/5=0.20 恰触绝对门上限（含等号）
        Draft draft = evaluator.evaluate(id, nativeSamples, control(6, 1), null, POLICY);

        assertThat(draft.eligibleIncidents()).isEqualTo(5);
        assertThat(draft.rawCounts().get("native_total")).isEqualTo(9);
        assertThat(draft.rawCounts().get("failed_incidents")).isEqualTo(1);
        assertThat(draft.strata().get("tenant.t1")).isEqualTo(5);
        assertThat(draft.strata().get("severity.critical")).isEqualTo(6);
        assertThat(draft.verdict()).isEqualTo("PASS");
    }

    @Test
    @DisplayName("缺数 INCONCLUSIVE：LIVE 独立样本不足 policy 最小值 → 不评判不落 FAIL")
    void insufficientSamplesAreInconclusive() {
        WindowIdentity id = identity(CanaryEvidenceClass.LIVE_CANARY, 1);
        List<Sample> samples = List.of(
                new Sample("inc-a", "t1", "critical", false, START),
                new Sample("inc-b", "t1", "critical", false, START));

        Draft draft = evaluator.evaluate(id, samples, control(6, 0), null, POLICY);

        assertThat(draft.verdict()).isEqualTo("INCONCLUSIVE");
        assertThat(draft.rawCounts().get("excluded_reason")).isEqualTo("INSUFFICIENT_SAMPLES");
    }

    @Test
    @DisplayName("缺数 INCONCLUSIVE：无同时段 control cohort → 相对门无从谈起")
    void missingControlIsInconclusive() {
        WindowIdentity id = identity(CanaryEvidenceClass.LIVE_CANARY, 1);
        List<Sample> samples = List.of(
                new Sample("inc-a", "t1", "critical", false, START),
                new Sample("inc-b", "t1", "critical", false, START),
                new Sample("inc-c", "t2", "critical", false, START),
                new Sample("inc-d", "t2", "critical", false, START),
                new Sample("inc-e", "t3", "critical", false, START));

        Draft draft = evaluator.evaluate(id, samples, List.of(), null, POLICY);

        assertThat(draft.verdict()).isEqualTo("INCONCLUSIVE");
        assertThat(draft.rawCounts().get("excluded_reason")).isEqualTo("NO_CONTROL_COHORT");
    }

    // ------------------------------------------------------------------ 双门与 critical 否决

    @Test
    @DisplayName("绝对 SLO 门：native 失败率超上限 → FAIL（control 再好也不豁免）")
    void absoluteSloGateFails() {
        WindowIdentity id = identity(CanaryEvidenceClass.LIVE_CANARY, 1);
        Draft draft = evaluator.evaluate(id, samples(10, 3), control(10, 0), null, POLICY);

        assertThat(draft.verdict()).isEqualTo("FAIL");
        assertThat(draft.absoluteSlo().get("gate")).isEqualTo("FAIL");
    }

    @Test
    @DisplayName("相对 control 门：native 显著劣于 control（超容差）→ FAIL")
    void relativeControlGateFails() {
        WindowIdentity id = identity(CanaryEvidenceClass.LIVE_CANARY, 1);
        // 10% vs control 0%：绝对门过（≤20%），相对门 0.10 > 0.00+0.05 → FAIL
        Draft draft = evaluator.evaluate(id, samples(10, 1), control(10, 0), null, POLICY);

        assertThat(draft.verdict()).isEqualTo("FAIL");
        assertThat(draft.control().get("gate")).isEqualTo("FAIL");
    }

    @Test
    @DisplayName("critical 否决优先：安全门 FALSE → FAIL（即使双门全过）")
    void criticalFailureVetoesPass() {
        WindowIdentity id = identity(CanaryEvidenceClass.LIVE_CANARY, 1);
        Draft draft = evaluator.evaluate(id, samples(10, 0), control(10, 0), false, POLICY);

        assertThat(draft.verdict()).isEqualTo("FAIL");
        assertThat(draft.criticalPass()).isFalse();
    }

    // ------------------------------------------------------------------ 连续 K 窗与作废

    @Test
    @DisplayName("连续 K 窗：仅 LIVE_CANARY 计入（DRILL/REPLAY 全优也无晋升资格，INV-AM6-5）")
    void onlyLiveCanaryCountsTowardStreak() {
        // LIVE 窗 seq 1/3/5 被 DRILL(2)/REPLAY(4) 占槽断开——非 LIVE 证据不延续链
        List<Draft> mixed = new ArrayList<>();
        mixed.add(pass(CanaryEvidenceClass.LIVE_CANARY, 1));
        mixed.add(pass(CanaryEvidenceClass.DRILL, 2));
        mixed.add(pass(CanaryEvidenceClass.LIVE_CANARY, 3));
        mixed.add(pass(CanaryEvidenceClass.REPLAY, 4));
        mixed.add(pass(CanaryEvidenceClass.LIVE_CANARY, 5));

        assertThat(CanaryWindowEvaluator.hasConsecutivePasses(mixed, 2)).isFalse();
        assertThat(CanaryWindowEvaluator.hasConsecutivePasses(mixed, 1)).isTrue();
    }

    @Test
    @DisplayName("连续 K 窗：FAIL/INCONCLUSIVE 断链；四 digest 任一变化 = 新身份（作废矩阵）")
    void streakBreaksOnFailOrIdentityChange() {
        List<Draft> streak = List.of(
                pass(CanaryEvidenceClass.LIVE_CANARY, 1),
                fail(CanaryEvidenceClass.LIVE_CANARY, 2),
                pass(CanaryEvidenceClass.LIVE_CANARY, 3),
                pass(CanaryEvidenceClass.LIVE_CANARY, 4));
        assertThat(CanaryWindowEvaluator.hasConsecutivePasses(streak, 2)).isTrue();
        assertThat(CanaryWindowEvaluator.hasConsecutivePasses(streak, 3)).isFalse();

        // candidate_digest 变化 = 新行为候选 → 同 seq 的 PASS 不续旧链
        WindowIdentity changed = new WindowIdentity(UUID.randomUUID(), "d".repeat(64),
                "p".repeat(64), "c".repeat(64), 1, 10, 3,
                CanaryEvidenceClass.LIVE_CANARY, START, END);
        List<Draft> digested = List.of(
                pass(CanaryEvidenceClass.LIVE_CANARY, 1),
                pass(CanaryEvidenceClass.LIVE_CANARY, 2),
                new Draft(changed, 5, Map.of(), Map.of(), Map.of(), Map.of(), null, "PASS"));
        assertThat(CanaryWindowEvaluator.hasConsecutivePasses(digested, 3)).isFalse();
    }

    // ------------------------------------------------------------------ 策略装载（fail-closed）

    @Test
    @DisplayName("策略装载：canary.window 段缺失/键缺/非法 → empty（禁硬编码默认值 O-63）")
    void policyFromBundleIsFailClosed() {
        assertThat(CanaryWindowPolicy.fromBundle(Map.of())).isEmpty();
        assertThat(CanaryWindowPolicy.fromBundle(Map.of("window", Map.of(
                "min_samples", 5)))).isEmpty();
        assertThat(CanaryWindowPolicy.fromBundle(Map.of("window", Map.of(
                "min_samples", 5, "consecutive_windows", 3, "window_minutes", 1440,
                "max_absolute_fail_rate", 0.2, "relative_tolerance", 0.05))))
                .isPresent();
        Map<String, Object> illegal = new LinkedHashMap<>();
        illegal.put("window", Map.of(
                "min_samples", "five", "consecutive_windows", 3, "window_minutes", 1440,
                "max_absolute_fail_rate", 0.2, "relative_tolerance", 0.05));
        assertThat(CanaryWindowPolicy.fromBundle(illegal)).isEmpty();
    }

    // ------------------------------------------------------------------ 夹具

    private static WindowIdentity identity(CanaryEvidenceClass clazz, int seq) {
        return new WindowIdentity(UUID.randomUUID(), "a".repeat(64), "b".repeat(64),
                "c".repeat(64), 1, 10, seq, clazz, START, END);
    }

    /** n 个独立事件，f 个失败 */
    private static List<Sample> samples(int incidents, int failed) {
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < incidents; i++) {
            out.add(new Sample("inc-" + i, "t" + (i % 3), "critical", i < failed, START));
        }
        return out;
    }

    /** control cohort：c 个样本 f 个失败 */
    private static List<Sample> control(int total, int failed) {
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            out.add(new Sample("ctl-" + i, "t" + (i % 3), "critical", i < failed, START));
        }
        return out;
    }

    private static Draft pass(CanaryEvidenceClass clazz, int seq) {
        return new Draft(identity(clazz, seq), 5, raw("PASS", null), Map.of(), Map.of(),
                Map.of(), true, "PASS");
    }

    private static Draft fail(CanaryEvidenceClass clazz, int seq) {
        return new Draft(identity(clazz, seq), 5, raw("FAIL", null), Map.of(), Map.of(),
                Map.of(), false, "FAIL");
    }

    private static Map<String, Object> raw(String verdict, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verdict", verdict);
        if (reason != null) {
            out.put("excluded_reason", reason);
        }
        return out;
    }
}
