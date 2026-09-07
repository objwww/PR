package com.objwww.pr.control.alert.application.reconcile;

import java.util.Objects;

/**
 * Incident Source Reconciler（AM4 M4-36）：低频核对事故源 alert 是否仍在当前
 * firing 集合中，<b>只追加 Observation</b>（本组件无 incident 写出口——RESOLVED
 * 是建议信号，incident 状态迁移归 incident 域所有者执行）。收敛条件双闸：
 * 连续缺席达阈值 <b>且</b> 距<b>首次缺席</b>的宽限窗届满——单次缺席绝不 resolved
 * （瞬时抓取缺失不放大），窗口起点锚定首次缺席而非阈值次（防"慢慢逼近"式早收敛）；
 * 出现即清零重计。轮询节奏/低频由调用方（M4-37 预算统一件）控制。
 *
 * @author wanghua
 * @date 2026-09-05
 */
public class IncidentSourceReconciler {

    /** 当前 alerts 只读源（低频核对入口） */
    public interface AlertSource {

        boolean isFiring(String alertFingerprint);
    }

    /** 观测账本（只追加） */
    public interface ObservationSink {

        void append(Observation observation);
    }

    /** 一轮核对产生的观测记录（追加进账本，含决策与缺席计数快照） */
    public record Observation(String incidentId, String alertFingerprint, boolean present,
            long consecutiveAbsences, long observedAtMillis, Decision decision) {
    }

    /** 收敛决策建议：PRESENT（在 firing）/ ABSENT_WITHIN_GRACE（宽限内缺席）/ RESOLVED */
    public enum Decision {PRESENT, ABSENT_WITHIN_GRACE, RESOLVED}

    /** 一轮核对结局：决策建议 + 下一轮状态（调用方持久化） */
    public record ReconcileOutcome(Decision decision, long nextConsecutiveAbsences,
            long nextFirstAbsentAtMillis) {
    }

    private final int absenceThreshold;
    private final long graceWindowMillis;
    private final AlertSource alerts;
    private final ObservationSink sink;

    public IncidentSourceReconciler(int absenceThreshold, long graceWindowMillis,
            AlertSource alerts, ObservationSink sink) {
        if (absenceThreshold < 1) {
            throw new IllegalArgumentException("缺席阈值必须 ≥1，实际: " + absenceThreshold);
        }
        if (graceWindowMillis < 0) {
            throw new IllegalArgumentException("宽限窗必须 ≥0，实际: " + graceWindowMillis);
        }
        this.absenceThreshold = absenceThreshold;
        this.graceWindowMillis = graceWindowMillis;
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /**
     * 一轮核对：查当前 alerts → 追加恰好一条 Observation → 返回决策建议与新状态。
     *
     * @param consecutiveAbsences   进入本轮前的连续缺席计数（0 = 无缺席史）
     * @param firstAbsentAtMillis   首次缺席时间（0 = 无缺席史；窗口起点，锚定后不漂移）
     * @param nowMillis             本轮核对时刻（调用方时钟，低频节奏由调用方控制）
     */
    public ReconcileOutcome reconcile(String incidentId, String alertFingerprint,
            long consecutiveAbsences, long firstAbsentAtMillis, long nowMillis) {
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(alertFingerprint, "alertFingerprint");
        boolean present = alerts.isFiring(alertFingerprint);
        Decision decision;
        long nextAbsences;
        long nextFirstAbsent = firstAbsentAtMillis;
        if (present) {
            decision = Decision.PRESENT;
            nextAbsences = 0;
            nextFirstAbsent = 0;
        } else {
            nextAbsences = consecutiveAbsences + 1;
            if (nextFirstAbsent == 0) {
                nextFirstAbsent = nowMillis; // 窗口起点锚定首次缺席
            }
            boolean thresholdMet = nextAbsences >= absenceThreshold;
            boolean graceElapsed = nowMillis - nextFirstAbsent >= graceWindowMillis;
            decision = thresholdMet && graceElapsed
                    ? Decision.RESOLVED : Decision.ABSENT_WITHIN_GRACE;
        }
        sink.append(new Observation(incidentId, alertFingerprint, present, nextAbsences,
                nowMillis, decision));
        return new ReconcileOutcome(decision, nextAbsences, nextFirstAbsent);
    }
}
