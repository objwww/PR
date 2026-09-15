package com.objwww.pr.control.alert.domain.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 决策溯源统一字段族（PA-A5，矩阵 L8-5 / B v2 L8.2 Decision Provenance）：
 * 关键决策事件携带统一 provenance 块——半年后可回答"INC-xxx 为什么对 xxx 执行了 xxx"。
 *
 * <p>口径：全字段可选（null 不出现在 JSON）；canonical 序列化键序固定（审计比对面）；
 * 不含任何载荷内容/密钥（只含身份与版本锚）。落点（渐进采纳）：
 * TOOL_INTENT_VALIDATED（意图事件）为首个采纳点；模型面 provenance 由
 * rca_model_call 账本行自带（role/version/digest/promptDigest/actualModel/routeId）。
 */
public record DecisionProvenance(
        String agentBuildSha,
        String policyVersion,
        String promptVersion,
        String modelProvider,
        String modelId,
        String toolName,
        String toolVersion,
        String toolSchemaHash) {

    public DecisionProvenance {
        Objects.requireNonNull(policyVersion, "policyVersion");
    }

    public static DecisionProvenance empty(String policyVersion) {
        return new DecisionProvenance(null, policyVersion, null, null, null, null, null, null);
    }

    public DecisionProvenance withTool(String name, String version, String schemaHash) {
        return new DecisionProvenance(agentBuildSha, policyVersion, promptVersion,
                modelProvider, modelId, name, version, schemaHash);
    }

    public DecisionProvenance withModel(String provider, String id) {
        return new DecisionProvenance(agentBuildSha, policyVersion, promptVersion,
                provider, id, toolName, toolVersion, toolSchemaHash);
    }

    public DecisionProvenance withPrompt(String version) {
        return new DecisionProvenance(agentBuildSha, policyVersion, version,
                modelProvider, modelId, toolName, toolVersion, toolSchemaHash);
    }

    public DecisionProvenance withAgentBuildSha(String sha) {
        return new DecisionProvenance(sha, policyVersion, promptVersion,
                modelProvider, modelId, toolName, toolVersion, toolSchemaHash);
    }

    /** canonical JSON：固定键序、null 省略（无 Jackson 依赖，域内自足） */
    public String toCanonicalJson() {
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("agent_build_sha", agentBuildSha);
        ordered.put("policy_version", policyVersion);
        ordered.put("prompt_version", promptVersion);
        ordered.put("model_provider", modelProvider);
        ordered.put("model_id", modelId);
        ordered.put("tool_name", toolName);
        ordered.put("tool_version", toolVersion);
        ordered.put("tool_schema_hash", toolSchemaHash);
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : ordered.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(entry.getKey()).append("\":\"")
                    .append(escape(entry.getValue())).append('"');
        }
        return json.append('}').toString();
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
