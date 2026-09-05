package com.objwww.pr.control.eval.domain.litellm;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * LiteLLM SpendLogs 单行投影（M3-25 对账输入；列形状按 spike Exp5 实测的
 * litellm 1.89.0 表结构 {@code LiteLLM_SpendLogs}，见
 * docs/测试证据/AM3/spike-holmes-budget/evidence/exp5_spendlogs_schema.txt）。
 *
 * <p>防御性解析纪律（M3-05 同款）：未知字段一律忽略；缺失/类型不符的字段落 null
 * ——对账器对 null 语义是"无法核验"（PARTIAL），绝不猜默认值。
 * 注意 1.89.0 没有独立 api_key_alias 列：key 别名只存在于 metadata JSON 的
 * {@code user_api_key_alias}，attempt/run 标签在
 * {@code metadata.spend_logs_metadata.{run_id,attempt_id}}（spike §二.4 实测）。
 */
public record SpendRecord(String requestId,
                          String model,
                          BigDecimal spend,
                          Integer promptTokens,
                          Integer completionTokens,
                          String status,
                          String keyAlias,
                          String metadataRunId,
                          String metadataAttemptId) {

    public SpendRecord {
        // requestId 允许 null（防御解析不因残行上抛）；其余字段本就 nullable
    }

    /** 兼容裸数组与 {data:[...]} 两种响应壳（版本差异防御）；非数组内容忽略 */
    public static List<SpendRecord> listFromJson(JsonNode body) {
        JsonNode array = body.isArray() ? body : body.path("data");
        List<SpendRecord> rows = new ArrayList<>();
        if (!array.isArray()) {
            return rows;
        }
        for (JsonNode row : array) {
            rows.add(fromJson(row));
        }
        return rows;
    }

    public static SpendRecord fromJson(JsonNode row) {
        Objects.requireNonNull(row, "row");
        JsonNode metadata = row.path("metadata");
        JsonNode spendMeta = metadata.path("spend_logs_metadata");
        return new SpendRecord(
                textOrNull(row, "request_id"),
                textOrNull(row, "model"),
                row.path("spend").isNumber()
                        ? row.path("spend").decimalValue() : null,
                intOrNull(row, "prompt_tokens"),
                intOrNull(row, "completion_tokens"),
                textOrNull(row, "status"),
                textOrNull(metadata, "user_api_key_alias"),
                textOrNull(spendMeta, "run_id"),
                textOrNull(spendMeta, "attempt_id"));
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : null;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        return node.path(field).isInt() ? node.path(field).asInt() : null;
    }
}
