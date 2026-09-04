package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * jsonb 自由文本列的 String 标量编解码(G0-10 E2E 发现:错误明细等 String 参数直写 jsonb 列
 * 被 PG 拒绝——varchar 无到 jsonb 的隐式转换;且错误文本多为非 JSON 平文,单靠 CAST 也会
 * invalid input syntax)。
 *
 * <p>写侧:encode 成 JSON 字符串字面量 + SQL 里 CAST(:param AS jsonb);读侧:decode 还原平文。
 * 与既有标签/annotations 列的 CAST 模式同构(那两类的值本身已是合法 JSON 文本)。
 */
final class JsonbText {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonbText() {
    }

    /** 平文 → JSON 字符串字面量(带引号与转义);null 透传 */
    static String encode(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(raw);
        } catch (Exception e) {
            return "\"unserializable-error-text\"";
        }
    }

    /** JSON 字符串字面量 → 平文;非预期形态原样返回(不因读侧抛错阻断链路) */
    static String decode(String jsonScalar) {
        if (jsonScalar == null) {
            return null;
        }
        try {
            return MAPPER.readValue(jsonScalar, String.class);
        } catch (Exception e) {
            return jsonScalar;
        }
    }
}
