package com.objwww.pr.control.eval.domain;

import com.objwww.pr.control.eval.domain.ScenarioMetrics.ScenarioScore;
import com.objwww.pr.control.eval.domain.ScenarioMetrics.ScoringVerdict;
import com.objwww.pr.control.eval.domain.ScenarioMetrics.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * M3-11~13 三指标冻结公式（§6.4）：coverage 全矩阵、conditional_accuracy 的
 * UNRESOLVED 不进分母与零分母约定、end_to_end 原始计数 + 公式快照一致性、
 * 结构失败/缺席的分桶语义（计 0 分入总分母、单独标注不混桶）。
 */
class ScenarioMetricsTest {

    private static ScenarioScore score(ScoringVerdict verdict, boolean hit) {
        return new ScenarioScore("S-" + verdict + "-" + hit, verdict, hit);
    }

    @Test
    @DisplayName("M3-11 coverage 全矩阵：命中/未命中/拒答/结构失败/缺席五态组合")
    void coverageAcrossAllVerdictBuckets() {
        Snapshot allHit = ScenarioMetrics.of(List.of(
                score(ScoringVerdict.DECIDABLE, true),
                score(ScoringVerdict.DECIDABLE, true)));
        assertThat(allHit.coverage()).isEqualTo(1.0);
        assertThat(allHit.conditionalAccuracy()).isEqualTo(1.0);
        assertThat(allHit.endToEndHitRate()).isEqualTo(1.0);
        assertThat(allHit.unresolvedRate()).isEqualTo(0.0);

        Snapshot allMiss = ScenarioMetrics.of(List.of(
                score(ScoringVerdict.DECIDABLE, false),
                score(ScoringVerdict.DECIDABLE, false)));
        assertThat(allMiss.coverage()).isEqualTo(1.0);
        assertThat(allMiss.conditionalAccuracy()).isEqualTo(0.0);
        assertThat(allMiss.endToEndHitRate()).isEqualTo(0.0);

        Snapshot mixed = ScenarioMetrics.of(List.of(
                score(ScoringVerdict.DECIDABLE, true),
                score(ScoringVerdict.DECIDABLE, true),
                score(ScoringVerdict.DECIDABLE, false),
                score(ScoringVerdict.UNRESOLVED, false),
                score(ScoringVerdict.STRUCTURE_REJECTED, false)));
        assertThat(mixed.coverage()).isEqualTo(3.0 / 5.0);
        assertThat(mixed.conditionalAccuracy()).isEqualTo(2.0 / 3.0);
        assertThat(mixed.endToEndHitRate()).isEqualTo(2.0 / 5.0);
        assertThat(mixed.unresolvedRate()).isEqualTo(1.0 / 5.0);
    }

    @Test
    @DisplayName("M3-12 conditional_accuracy：UNRESOLVED 不进分母；decidable=0 → 0.0 不产生 NaN")
    void conditionalAccuracyExcludesUnresolvedFromDenominator() {
        Snapshot cautious = ScenarioMetrics.of(List.of(
                score(ScoringVerdict.DECIDABLE, true),
                score(ScoringVerdict.UNRESOLVED, false),
                score(ScoringVerdict.UNRESOLVED, false)));
        assertThat(cautious.conditionalAccuracy()).isEqualTo(1.0);
        assertThat(cautious.coverage()).isEqualTo(1.0 / 3.0);
        assertThat(cautious.endToEndHitRate()).isEqualTo(1.0 / 3.0);
        assertThat(cautious.unresolvedRate()).isEqualTo(2.0 / 3.0);

        Snapshot allRefused = ScenarioMetrics.of(List.of(
                score(ScoringVerdict.UNRESOLVED, false),
                score(ScoringVerdict.UNRESOLVED, false)));
        assertThat(allRefused.coverage()).isZero();
        assertThat(allRefused.conditionalAccuracy()).isZero();
        assertThat(allRefused.endToEndHitRate()).isZero();
        assertThat(allRefused.unresolvedRate()).isEqualTo(1.0);

        assertThat(ScenarioMetrics.of(List.of()).coverage()).isZero();
        assertThat(ScenarioMetrics.of(List.of()).conditionalAccuracy()).isZero();
        assertThat(ScenarioMetrics.of(List.of()).endToEndHitRate()).isZero();
        assertThat(ScenarioMetrics.of(List.of()).total()).isZero();
    }

    @Test
    @DisplayName("M3-13 分桶语义：结构失败/缺席计 0 入总分母、分别计数不混桶；原始计数守恒")
    void structureRejectedAndAbsentAreCountedSeparately() {
        Snapshot rejected = ScenarioMetrics.of(List.of(
                score(ScoringVerdict.STRUCTURE_REJECTED, false),
                score(ScoringVerdict.STRUCTURE_REJECTED, false),
                score(ScoringVerdict.STRUCTURE_REJECTED, false)));
        assertThat(rejected.total()).isEqualTo(3);
        assertThat(rejected.structureRejected()).isEqualTo(3);
        assertThat(rejected.coverage()).isZero();
        assertThat(rejected.conditionalAccuracy()).isZero();
        assertThat(rejected.endToEndHitRate()).isZero();

        Snapshot allFour = ScenarioMetrics.of(List.of(
                score(ScoringVerdict.DECIDABLE, true),
                score(ScoringVerdict.UNRESOLVED, false),
                score(ScoringVerdict.STRUCTURE_REJECTED, false),
                score(ScoringVerdict.TIMEOUT_OR_ABSENT, false)));
        assertThat(allFour.total()).isEqualTo(4);
        assertThat(allFour.decidable() + allFour.unresolved()
                + allFour.structureRejected() + allFour.timeoutOrAbsent())
                .isEqualTo(allFour.total());
        assertThat(allFour.decidable()).isEqualTo(1);
        assertThat(allFour.unresolved()).isEqualTo(1);
        assertThat(allFour.structureRejected()).isEqualTo(1);
        assertThat(allFour.timeoutOrAbsent()).isEqualTo(1);
        assertThat(allFour.hits()).isEqualTo(1);
    }

    @Test
    @DisplayName("公式快照一致性：end_to_end = coverage × conditional_accuracy（二进制精确值 + 近似值）")
    void snapshotCarriesRawCountsAndFormulaIdentity() {
        Snapshot exact = ScenarioMetrics.of(List.of(
                score(ScoringVerdict.DECIDABLE, true),
                score(ScoringVerdict.DECIDABLE, false),
                score(ScoringVerdict.UNRESOLVED, false),
                score(ScoringVerdict.STRUCTURE_REJECTED, false)));
        assertThat(exact.coverage()).isEqualTo(0.5);
        assertThat(exact.conditionalAccuracy()).isEqualTo(0.5);
        assertThat(exact.endToEndHitRate()).isEqualTo(0.25);
        assertThat(exact.endToEndHitRate())
                .isEqualTo(exact.coverage() * exact.conditionalAccuracy());

        Snapshot approximate = ScenarioMetrics.of(List.of(
                score(ScoringVerdict.DECIDABLE, true),
                score(ScoringVerdict.DECIDABLE, true),
                score(ScoringVerdict.DECIDABLE, false),
                score(ScoringVerdict.UNRESOLVED, false),
                score(ScoringVerdict.STRUCTURE_REJECTED, false)));
        assertThat(approximate.endToEndHitRate())
                .isCloseTo(approximate.coverage() * approximate.conditionalAccuracy(),
                        within(1e-12));
    }

    @Test
    @DisplayName("防御：非 DECIDABLE 的 rootCauseHit=true 不计分子（hits 只在 DECIDABLE 分支累加）")
    void hitFlagOutsideDecidableIsIgnored() {
        Snapshot snapshot = ScenarioMetrics.of(List.of(
                score(ScoringVerdict.UNRESOLVED, true),
                score(ScoringVerdict.STRUCTURE_REJECTED, true),
                score(ScoringVerdict.TIMEOUT_OR_ABSENT, true)));
        assertThat(snapshot.hits()).isZero();
        assertThat(snapshot.endToEndHitRate()).isZero();
    }
}
