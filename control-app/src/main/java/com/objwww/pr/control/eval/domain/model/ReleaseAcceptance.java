package com.objwww.pr.control.eval.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * 发布验收输入/结果值对象（ME-T09/D09 步骤 7；纯数据，L0 零框架依赖）。
 *
 * <p>流程执行状态（{@link PipelineState}，SUCCEEDED/FAILED）与四面质量结论
 * （质量/安全/行为/证据完整性）<b>始终分别承载</b>——SUCCEEDED 只说流程跑完，
 * 永不代表质量通过（STAT-02/03 分展面）。面结论三态：PASS/FAIL/INCONCLUSIVE
 * （小样本或关键层未覆盖 = INCONCLUSIVE，不冒充 PASS）。
 */
public record ReleaseAcceptance(PipelineState pipelineState,
                                FaceVerdict quality,
                                FaceVerdict safety,
                                FaceVerdict behavior,
                                FaceVerdict evidenceCompleteness,
                                Qualification qualification,
                                List<String> reasons) {

    /** 流程执行状态（与质量结论分展，不合并） */
    public enum PipelineState {
        SUCCEEDED,
        FAILED
    }

    /** 单面结论三态 */
    public enum FaceVerdict {
        PASS,
        FAIL,
        INCONCLUSIVE
    }

    /** 最终发布资格（四面合成；INCONCLUSIVE 也是一条可审计结论） */
    public enum Qualification {
        QUALIFIED,
        NOT_QUALIFIED,
        INCONCLUSIVE
    }

    public ReleaseAcceptance {
        Objects.requireNonNull(pipelineState, "pipelineState 不得为 null");
        Objects.requireNonNull(quality, "quality 不得为 null");
        Objects.requireNonNull(safety, "safety 不得为 null");
        Objects.requireNonNull(behavior, "behavior 不得为 null");
        Objects.requireNonNull(evidenceCompleteness, "evidenceCompleteness 不得为 null");
        Objects.requireNonNull(qualification, "qualification 不得为 null");
        Objects.requireNonNull(reasons, "reasons 不得为 null");
        reasons = List.copyOf(reasons);
    }

    /** 质量面输入：关键分层逐层结果（covered=false = 该层未覆盖 → 面 INCONCLUSIVE；
     *  covered=true 且 regressed=true = 关键层退化 → 面 FAIL，不被总体均分掩盖） */
    public record StratumResult(String stratumId, boolean covered, boolean regressed) {

        public StratumResult {
            Objects.requireNonNull(stratumId, "stratumId 不得为 null");
            if (stratumId.isBlank()) {
                throw new IllegalArgumentException("stratumId 不得为 blank");
            }
        }
    }
}
