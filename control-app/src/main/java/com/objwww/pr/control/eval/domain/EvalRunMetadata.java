package com.objwww.pr.control.eval.domain;

import com.objwww.pr.shared.Digest;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * EvalRun 十项可复现元数据（M3-14 冻结清单）：一次批量评测的模型/提示词/工具面/
 * 采样参数/种子/provider/规则集/驱动器版本全集。
 *
 * <p>{@link #configDigest()} 按固定字段序做 canonical 行摘要——同配置重跑 digest
 * 一致（不可覆盖语义的重跑锚，M3-14 验收）；任何一项变化 → digest 变化（跨批次
 * 不可比即被 digest 暴露）。可空采样参数（temperature/top_p/max_tokens/种子）在
 * digest 中以字面 {@code null} 参与行序化——"未配置"与"配置为 0"可区分。
 */
public record EvalRunMetadata(int schemaVersion,
                              String datasetVersion,
                              Digest registryDigest,
                              int lexiconVersion,
                              String model,
                              String promptVersion,
                              Digest promptDigest,
                              Digest toolRegistryDigest,
                              BigDecimal temperature,
                              BigDecimal topP,
                              Integer maxTokens,
                              Long requestedSeed,
                              Long effectiveSeed,
                              String providerFingerprint,
                              Digest alertRuleDigest,
                              String scenarioDriverVersion) {

    public EvalRunMetadata {
        Objects.requireNonNull(datasetVersion, "dataset_version 不得为 null");
        Objects.requireNonNull(registryDigest, "registry_digest 不得为 null");
        Objects.requireNonNull(model, "model 不得为 null");
        Objects.requireNonNull(promptVersion, "prompt_version 不得为 null");
        Objects.requireNonNull(promptDigest, "prompt_digest 不得为 null");
        Objects.requireNonNull(toolRegistryDigest, "tool_registry_digest 不得为 null");
        Objects.requireNonNull(providerFingerprint, "provider_fingerprint 不得为 null");
        Objects.requireNonNull(alertRuleDigest, "alert_rule_digest 不得为 null");
        Objects.requireNonNull(scenarioDriverVersion, "scenario_driver_version 不得为 null");
        if (schemaVersion <= 0 || lexiconVersion <= 0) {
            throw new IllegalArgumentException("schema_version/lexicon_version 必须为正");
        }
    }

    /** 十项元数据的固定字段序 canonical 摘要（重跑一致锚；不依赖构造顺序） */
    public Digest configDigest() {
        String canonical = "eval-run-meta/v1"
                + "|schema=" + schemaVersion
                + "|dataset=" + datasetVersion
                + "|registry=" + registryDigest.value()
                + "|lexicon=" + lexiconVersion
                + "|model=" + model
                + "|prompt=" + promptVersion + ":" + promptDigest.value()
                + "|tools=" + toolRegistryDigest.value()
                + "|temperature=" + dec(temperature)
                + "|topP=" + dec(topP)
                + "|maxTokens=" + dec(maxTokens)
                + "|requestedSeed=" + dec(requestedSeed)
                + "|effectiveSeed=" + dec(effectiveSeed)
                + "|provider=" + providerFingerprint
                + "|rules=" + alertRuleDigest.value()
                + "|driver=" + scenarioDriverVersion;
        return Digest.sha256Of(canonical);
    }

    private static String dec(Object value) {
        if (value == null) {
            return "null";
        }
        return value instanceof BigDecimal bd
                ? bd.stripTrailingZeros().toPlainString()
                : value.toString();
    }
}
