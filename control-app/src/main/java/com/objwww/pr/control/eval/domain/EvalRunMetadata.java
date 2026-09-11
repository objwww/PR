package com.objwww.pr.control.eval.domain;

import com.objwww.pr.shared.Digest;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * EvalRun 十项可复现元数据（M3-14 冻结清单 + EN-09 评分器版本）：一次批量评测的
 * 模型/提示词/工具面/采样参数/种子/provider/规则集/驱动器版本/评分器版本全集。
 *
 * <p>{@link #configDigest()} 按固定字段序做 canonical 行摘要——同配置重跑 digest
 * 一致（不可覆盖语义的重跑锚，M3-14 验收）；任何一项变化 → digest 变化（跨批次
 * 不可比即被 digest 暴露）。可空采样参数（temperature/top_p/max_tokens/种子）在
 * digest 中以字面 {@code null} 参与行序化——"未配置"与"配置为 0"可区分。
 * EN-09：{@code graderVersion} 入身份面——历史分数按评分器版本归属（E11：换评分器
 * 重评分 = 新记录，不覆盖原证明）；null = EN-09 前历史批次（可区分、不可冒充新版
 * 证据）；生产批次经 {@code app.alert.eval.grader-version} 必填注入（fail-closed）。
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
                              String scenarioDriverVersion,
                              String graderVersion) {

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
        StringBuilder canonical = new StringBuilder("eval-run-meta/v1");
        canonicalMap().forEach((key, value) ->
                canonical.append('|').append(key).append('=').append(render(value)));
        return Digest.sha256Of(canonical.toString());
    }

    /** 报告面投影（M3-18 全配置版本；固定字段序 LinkedHashMap） */
    public Map<String, Object> canonicalMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schema_version", schemaVersion);
        map.put("dataset_version", datasetVersion);
        map.put("registry_digest", registryDigest.value());
        map.put("lexicon_version", lexiconVersion);
        map.put("model", model);
        map.put("prompt_version", promptVersion);
        map.put("prompt_digest", promptDigest.value());
        map.put("tool_registry_digest", toolRegistryDigest.value());
        map.put("temperature", render(temperature));
        map.put("top_p", render(topP));
        map.put("max_tokens", render(maxTokens));
        map.put("requested_seed", render(requestedSeed));
        map.put("effective_seed", render(effectiveSeed));
        map.put("provider_fingerprint", providerFingerprint);
        map.put("alert_rule_digest", alertRuleDigest.value());
        map.put("scenario_driver_version", scenarioDriverVersion);
        // 可空成员走 render（字面 "null"）：EN-09 前历史批次与显式版本在身份面可区分
        map.put("grader_version", render(graderVersion));
        return map;
    }

    private static Object render(Object value) {
        if (value == null) {
            return "null";
        }
        return value instanceof BigDecimal bd
                ? bd.stripTrailingZeros().toPlainString()
                : value;
    }
}
