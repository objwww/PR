package com.objwww.pr.control.eval.domain;

import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * EvalRun 聚合（M3-14）：一次批量评测的头记录。
 *
 * <p>生命周期：RUNNING（开跑即插，无任何聚合）→ SUCCEEDED（终态化一次性回填
 * 原始计数 + 三指标分子分母）/ FAILED（中途夭折，允许无指标）。终态化在仓储层
 * 以 {@code state='RUNNING'} CAS 收口——0 行 = 已终结，拒绝（同 EvalRun 不可覆盖）。
 */
public record EvalRun(UUID id,
                      EvalRunMetadata metadata,
                      EvalRunState state,
                      Instant startedAt,
                      Instant finishedAt,
                      ScenarioMetrics.Snapshot summary,
                      SymptomCounts symptomCounts,
                      Digest baselineReportDigest,
                      String terminalReason) {

    public enum EvalRunState {RUNNING, SUCCEEDED, FAILED}

    /** 症状维度原始 TP/FP/FN（期望症状命中 / 多报 / 漏报；逐案例累加） */
    public record SymptomCounts(int truePositives, int falsePositives, int falseNegatives) {
        public SymptomCounts {
            if (truePositives < 0 || falsePositives < 0 || falseNegatives < 0) {
                throw new IllegalArgumentException("TP/FP/FN 计数不得为负");
            }
        }
    }

    public EvalRun {
        Objects.requireNonNull(id, "id 不得为 null");
        Objects.requireNonNull(metadata, "metadata 不得为 null");
        Objects.requireNonNull(state, "state 不得为 null");
        Objects.requireNonNull(startedAt, "started_at 不得为 null");
    }

    /** 开跑行：仅 RUNNING 形态可插入（聚合列全空，DB 生命周期约束兜底） */
    public static EvalRun running(UUID id, EvalRunMetadata metadata, Instant startedAt) {
        return new EvalRun(id, metadata, EvalRunState.RUNNING, startedAt, null,
                null, null, null, null);
    }

    /** 终态行（无卡因形态，兼容旧调用面）：SUCCEEDED 必带全套快照；FAILED 允许无快照 */
    public static EvalRun terminal(UUID id, EvalRunMetadata metadata, EvalRunState state,
                                   Instant startedAt, Instant finishedAt,
                                   ScenarioMetrics.Snapshot summary,
                                   SymptomCounts counts, Digest baselineReportDigest) {
        return terminal(id, metadata, state, startedAt, finishedAt,
                summary, counts, baselineReportDigest, null);
    }

    /**
     * 终态行（EV-04 卡因面）：SUCCEEDED 必带全套快照；FAILED 允许无快照（中途夭折）。
     * terminalReason = FAILED 的失败/取消原因（EU13 可读面）；SUCCEEDED 不得带卡因。
     */
    public static EvalRun terminal(UUID id, EvalRunMetadata metadata, EvalRunState state,
                                   Instant startedAt, Instant finishedAt,
                                   ScenarioMetrics.Snapshot summary,
                                   SymptomCounts counts, Digest baselineReportDigest,
                                   String terminalReason) {
        if (state == EvalRunState.RUNNING) {
            throw new IllegalArgumentException("terminal 不接受 RUNNING");
        }
        if (state == EvalRunState.SUCCEEDED && (summary == null || counts == null)) {
            throw new IllegalArgumentException("SUCCEEDED 必带指标快照与症状计数");
        }
        if (state == EvalRunState.SUCCEEDED && terminalReason != null) {
            throw new IllegalArgumentException("SUCCEEDED 不得携带 terminal_reason");
        }
        return new EvalRun(id, metadata, state, startedAt, finishedAt,
                summary, counts, baselineReportDigest, terminalReason);
    }
}
