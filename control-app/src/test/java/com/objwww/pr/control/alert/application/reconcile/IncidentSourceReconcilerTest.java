package com.objwww.pr.control.alert.application.reconcile;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Incident Source Reconciler 单测（AM4 M4-36，TDD 先行）：低频核对当前 alerts，
 * 每轮只追加一条 Observation；<b>单次缺席不 resolved</b>；连续缺席达阈值 <b>且</b>
 * 距首次缺席的宽限窗届满才收敛（窗口起点锚定首次缺席，不锚定阈值次）；出现即清零
 * 重计。收敛决策是建议信号——incident 状态迁移归 incident 域（本组件只追加
 * Observation，无 incident 写出口）。
 *
 * @author wanghua
 * @date 2026-09-05
 */
class IncidentSourceReconcilerTest {

    private static final String INCIDENT = "inc-001";
    private static final String FINGERPRINT = "fp-cpu-svc-a";
    private static final int ABSENCE_THRESHOLD = 3;
    private static final long GRACE_MILLIS = 10 * 60_000L;
    private static final long T0 = 1_790_000_000_000L;

    @Test
    void singleAbsenceDoesNotResolve() {
        RecordingSink sink = new RecordingSink(Set.of());
        IncidentSourceReconciler reconciler = reconciler(sink);

        IncidentSourceReconciler.ReconcileOutcome outcome =
                reconciler.reconcile(INCIDENT, FINGERPRINT, 0, 0, T0);

        assertThat(outcome.decision())
                .isEqualTo(IncidentSourceReconciler.Decision.ABSENT_WITHIN_GRACE);
        assertThat(outcome.nextConsecutiveAbsences()).isEqualTo(1);
        assertThat(outcome.nextFirstAbsentAtMillis()).isEqualTo(T0);
    }

    @Test
    void resolvesOnlyAfterThresholdAndGraceWindow() {
        // 第 1/2 次缺席：未达阈值或宽限窗未届满 → 都不收敛
        RecordingSink sink = new RecordingSink(Set.of());
        IncidentSourceReconciler reconciler = reconciler(sink);
        assertThat(reconciler.reconcile(INCIDENT, FINGERPRINT, 0, 0, T0).decision())
                .isEqualTo(IncidentSourceReconciler.Decision.ABSENT_WITHIN_GRACE);
        assertThat(reconciler.reconcile(INCIDENT, FINGERPRINT, 1, T0,
                T0 + 9 * 60_000L).decision())
                .isEqualTo(IncidentSourceReconciler.Decision.ABSENT_WITHIN_GRACE);

        // 第 3 次缺席且距首次缺席 ≥ 宽限窗 → 收敛（窗口起点锚定首次缺席）
        assertThat(reconciler.reconcile(INCIDENT, FINGERPRINT, 2, T0,
                T0 + GRACE_MILLIS).decision())
                .isEqualTo(IncidentSourceReconciler.Decision.RESOLVED);
    }

    @Test
    void thresholdMetButGraceWindowStillOpenDoesNotResolve() {
        RecordingSink sink = new RecordingSink(Set.of());
        IncidentSourceReconciler reconciler = reconciler(sink);

        // 连续 3 次缺席（达阈值）但仅距首次缺席 1 分钟（宽限窗未届满）→ 不收敛
        IncidentSourceReconciler.ReconcileOutcome outcome =
                reconciler.reconcile(INCIDENT, FINGERPRINT, 2, T0, T0 + 60_000L);

        assertThat(outcome.decision())
                .isEqualTo(IncidentSourceReconciler.Decision.ABSENT_WITHIN_GRACE);
        assertThat(outcome.nextConsecutiveAbsences()).isEqualTo(3);
    }

    @Test
    void presenceResetsAbsenceStreak() {
        RecordingSink sink = new RecordingSink(Set.of(FINGERPRINT));
        IncidentSourceReconciler reconciler = reconciler(sink);

        IncidentSourceReconciler.ReconcileOutcome outcome =
                reconciler.reconcile(INCIDENT, FINGERPRINT, 2, T0, T0 + 60_000L);

        assertThat(outcome.decision()).isEqualTo(IncidentSourceReconciler.Decision.PRESENT);
        assertThat(outcome.nextConsecutiveAbsences()).isZero();
        assertThat(outcome.nextFirstAbsentAtMillis()).isZero();
    }

    @Test
    void everyCycleAppendsExactlyOneObservation() {
        RecordingSink sink = new RecordingSink(Set.of());
        IncidentSourceReconciler reconciler = reconciler(sink);

        reconciler.reconcile(INCIDENT, FINGERPRINT, 0, 0, T0);
        reconciler.reconcile(INCIDENT, FINGERPRINT, 1, T0, T0 + 60_000L);

        assertThat(sink.observations).hasSize(2);
        IncidentSourceReconciler.Observation first = sink.observations.get(0);
        assertThat(first.incidentId()).isEqualTo(INCIDENT);
        assertThat(first.alertFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(first.present()).isFalse();
        assertThat(first.consecutiveAbsences()).isEqualTo(1);
        assertThat(first.decision())
                .isEqualTo(IncidentSourceReconciler.Decision.ABSENT_WITHIN_GRACE);
    }

    @Test
    void invalidConfigurationIsRejected() {
        RecordingSink sink = new RecordingSink(Set.of());
        assertThatThrownBy(() -> new IncidentSourceReconciler(0, GRACE_MILLIS, sink, sink))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IncidentSourceReconciler(2, -1, sink, sink))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ 夹具

    private static IncidentSourceReconciler reconciler(RecordingSink sink) {
        return new IncidentSourceReconciler(ABSENCE_THRESHOLD, GRACE_MILLIS, sink, sink);
    }

    /** 测试夹具：alerts 只读源 + 观测账本（只追加） */
    static final class RecordingSink implements IncidentSourceReconciler.AlertSource,
            IncidentSourceReconciler.ObservationSink {

        final Set<String> firingFingerprints;
        final List<IncidentSourceReconciler.Observation> observations = new ArrayList<>();

        RecordingSink(Set<String> firingFingerprints) {
            this.firingFingerprints = firingFingerprints;
        }

        @Override
        public boolean isFiring(String alertFingerprint) {
            return firingFingerprints.contains(alertFingerprint);
        }

        @Override
        public void append(IncidentSourceReconciler.Observation observation) {
            observations.add(observation);
        }
    }
}
