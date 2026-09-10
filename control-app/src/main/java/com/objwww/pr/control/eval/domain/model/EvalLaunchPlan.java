package com.objwww.pr.control.eval.domain.model;

import com.objwww.pr.shared.Digest;

import java.util.Objects;

/**
 * EV-04 发起计划（POST /api/eval/runs 请求体的领域形；eval_run.launch_plan 的
 * 快照内容与幂等冲突判据来源）。
 *
 * <p>诚实边界（偏差登记，不进代码冒充）：
 * <ul>
 *   <li>budget（maxTokens/maxConcurrency/deadlineSeconds）本期只持久化为计划约束，
 *       不做执行面强制（用量强制等 R7 RCA 调用账本，EV-06；RV08 红线）；</li>
 *   <li>candidate.model/promptVersion 为空 = 沿用 worker env 元数据（十项可复现
 *       元数据的 digest 面在 EvalRunMetadata.configDigest）；</li>
 *   <li>roundsPerScenario 空 = worker 默认轮次（现有 5×2 编排的 2）。</li>
 * </ul>
 *
 * <p>{@link #canonical()} 固定字段序行序化（EvalRunMetadata.configDigest 同式）——
 * 同计划同 digest：幂等键冲突时 payload_hash 相等 = 重放，不等 = 409（EU09）。
 */
public record EvalLaunchPlan(String displayName,
                             String mode,
                             String datasetVersion,
                             String model,
                             String promptVersion,
                             Long budgetMaxTokens,
                             Integer maxConcurrency,
                             Long deadlineSeconds,
                             Integer roundsPerScenario) {

    public EvalLaunchPlan {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName 必填");
        }
        if (!com.objwww.pr.control.eval.domain.statemachine.EvalRunLifecycle
                .modeLegal(mode)) {
            throw new IllegalArgumentException("mode 必为 E/B/L: " + mode);
        }
        if (datasetVersion == null || datasetVersion.isBlank()) {
            throw new IllegalArgumentException("datasetVersion 必填");
        }
        if (budgetMaxTokens != null && budgetMaxTokens <= 0) {
            throw new IllegalArgumentException("budgetMaxTokens 必须为正");
        }
        if (maxConcurrency != null && maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency 必须为正");
        }
        if (deadlineSeconds != null && deadlineSeconds <= 0) {
            throw new IllegalArgumentException("deadlineSeconds 必须为正");
        }
        if (roundsPerScenario != null && roundsPerScenario <= 0) {
            throw new IllegalArgumentException("roundsPerScenario 必须为正");
        }
    }

    /** 固定字段序 canonical 行（空值以字面 null 参与——"未填"与"填 0"可区分） */
    public String canonical() {
        return "eval-launch/v1"
                + "|displayName=" + displayName
                + "|mode=" + mode
                + "|datasetVersion=" + datasetVersion
                + "|model=" + Objects.toString(model, "null")
                + "|promptVersion=" + Objects.toString(promptVersion, "null")
                + "|budgetMaxTokens=" + Objects.toString(budgetMaxTokens, "null")
                + "|maxConcurrency=" + Objects.toString(maxConcurrency, "null")
                + "|deadlineSeconds=" + Objects.toString(deadlineSeconds, "null")
                + "|roundsPerScenario=" + Objects.toString(roundsPerScenario, "null");
    }

    /** 幂等冲突判据（uq 撞键后：同 digest = 重放，异 digest = 409） */
    public Digest payloadHash() {
        return Digest.sha256Of(canonical());
    }
}
