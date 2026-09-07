package com.objwww.pr.control.eval.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * EvaluationRecordV1（M5-08 落档模型；方案 §12.2：真实性标签必须写入）。
 * 一次批量评测过门后的完整门禁证据：六维 run 聚合原始计数 + 安全裁决快照 +
 * 配对统计溯源快照（INV-AM5-3：stats_seed/算法版本/重采样次数/CI 方法全带）+
 * 五分支结论与机器码未过原因（门禁解释完整性）+ digest 集（dataset/config/engine）。
 *
 * <p>insert-only：落档即冻结，重评 = 换新 record id（与 V10 eval_case_result 同纪律）。
 * 值快照形态（ViolationSnapshot/StatsSnapshot）有意不复用 service 层类型——
 * 本类型零框架且不反向依赖 service 包（避免 model↔service 包环），映射归
 * EvalGateRunner（application 编排面）。
 *
 * @param id                记录 id（insert-only 主键）
 * @param evalRunId         关联评测批
 * @param authenticity      真实性标签（LIVE_BUSINESS/HYBRID_BUSINESS/PUBLIC_REPLAY/
 *                          CONTROL_FIXTURE——CONTROL_FIXTURE 的 ELIGIBLE 结论不得冒充
 *                          Native 质量结论，§12.2）
 * @param sixDim            六维 run 聚合原始计数（含 traceRefs 可回溯）
 * @param safetyVerdict     安全裁决 PASS/REJECT（SafetyGate.Verdict 名）
 * @param safetyViolations  安全违规快照（face/ref/reason 逐条可回溯）
 * @param stats             配对统计溯源快照；null = 本批无配对试验（不伪造统计面）
 * @param outcome           五分支结论（REJECT/INCONCLUSIVE/ELIGIBLE_FOR_CANARY）
 * @param gateReasons       机器码未过原因（ELIGIBLE 时为空）
 * @param thresholdsVersion 阈值集版本（复现锚）
 * @param datasetDigest     数据集 digest
 * @param configDigest      配置 digest（eval_run.config_digest 同源）
 * @param engineDigest      引擎 digest
 * @param createdAt         落档时刻
 */
public record EvaluationRecordV1(UUID id,
                                 UUID evalRunId,
                                 AuthenticityLabel authenticity,
                                 SixDimResult sixDim,
                                 String safetyVerdict,
                                 List<ViolationSnapshot> safetyViolations,
                                 StatsSnapshot stats,
                                 Outcome outcome,
                                 List<String> gateReasons,
                                 String thresholdsVersion,
                                 String datasetDigest,
                                 String configDigest,
                                 String engineDigest,
                                 Instant createdAt) {

    /** 真实性标签（§12.2 冻结四值，写入套件 manifest 与本记录） */
    public enum AuthenticityLabel {
        LIVE_BUSINESS,
        HYBRID_BUSINESS,
        PUBLIC_REPLAY,
        CONTROL_FIXTURE
    }

    /** 五分支结论（在线 Canary 无 GT 的 disagreement 面不在本门禁消费面，INV-AM5-6） */
    public enum Outcome {
        REJECT,
        INCONCLUSIVE,
        ELIGIBLE_FOR_CANARY
    }

    /** 安全违规快照（与 SafetyGate.Violation 同构值面） */
    public record ViolationSnapshot(SafetyFace face, String ref, String reason) {

        public ViolationSnapshot {
            Objects.requireNonNull(face, "face 不得为 null");
            Objects.requireNonNull(ref, "ref 不得为 null");
            Objects.requireNonNull(reason, "reason 不得为 null");
        }
    }

    /**
     * 配对统计溯源快照（与 PairedTrialStats.StatsResult 同构值面；
     * INCONCLUSIVE 时区间字段为 null——如实悬挂不收窄假区间）。
     */
    public record StatsSnapshot(int clusterCount,
                                long statsSeed,
                                String algorithmVersion,
                                int resamples,
                                String ciMethod,
                                double pointEstimate,
                                Double ciLower,
                                Double ciUpper,
                                String verdict) {

        public StatsSnapshot {
            Objects.requireNonNull(algorithmVersion, "algorithmVersion 不得为 null");
            Objects.requireNonNull(ciMethod, "ciMethod 不得为 null");
            Objects.requireNonNull(verdict, "verdict 不得为 null");
        }
    }

    public EvaluationRecordV1 {
        Objects.requireNonNull(id, "id 不得为 null");
        Objects.requireNonNull(evalRunId, "evalRunId 不得为 null");
        Objects.requireNonNull(authenticity, "authenticity 不得为 null");
        Objects.requireNonNull(sixDim, "sixDim 不得为 null");
        Objects.requireNonNull(safetyVerdict, "safetyVerdict 不得为 null");
        Objects.requireNonNull(safetyViolations, "safetyViolations 不得为 null");
        safetyViolations = List.copyOf(safetyViolations);
        Objects.requireNonNull(outcome, "outcome 不得为 null");
        Objects.requireNonNull(gateReasons, "gateReasons 不得为 null");
        gateReasons = List.copyOf(gateReasons);
        if (outcome == Outcome.ELIGIBLE_FOR_CANARY && !gateReasons.isEmpty()) {
            throw new IllegalArgumentException("ELIGIBLE_FOR_CANARY 不得携带未过原因");
        }
        if (outcome != Outcome.ELIGIBLE_FOR_CANARY && gateReasons.isEmpty()) {
            throw new IllegalArgumentException("非 ELIGIBLE 结论必须携带未过原因（解释完整性）");
        }
        Objects.requireNonNull(thresholdsVersion, "thresholdsVersion 不得为 null");
        Objects.requireNonNull(datasetDigest, "datasetDigest 不得为 null");
        Objects.requireNonNull(configDigest, "configDigest 不得为 null");
        Objects.requireNonNull(engineDigest, "engineDigest 不得为 null");
        Objects.requireNonNull(createdAt, "createdAt 不得为 null");
    }
}
