package com.objwww.pr.control.domain.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 模型 findings JSON 解析器（domain 内部，Jackson 树模型手工取字段，不做绑定注解）。
 * 契约：模型输出一个 JSON 数组，元素含 file/line/existing_code/rule/severity/message。
 *
 * <p>两级容错：
 * <ul>
 *   <li>整体不是 JSON 数组 / 无法解析 → {@link ModelOutputParseException}（安全失败，Step FAILED）；</li>
 *   <li>单条缺关键锚点字段（file/existing_code）→ 跳过该条并计入 malformedCount
 *       （一条乱不应拖死整批；计数进 ReviewOutcome）。</li>
 * </ul>
 * 容忍 Markdown 代码栅栏：取文本中第一个 '[' 到最后一个 ']' 之间的内容解析。
 */
final class FindingJsonParser {

    /** 解析结果：合法条目 + 畸形跳过计数 */
    record ParseResult(List<ModelFinding> findings, int malformedCount) {
    }

    private final ObjectMapper mapper = new ObjectMapper();

    ParseResult parse(String content) {
        Objects.requireNonNull(content, "content");
        String json = extractJsonArray(content);
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new ModelOutputParseException("模型输出不是合法 JSON 数组", e);
        }
        if (!root.isArray()) {
            throw new ModelOutputParseException("模型输出不是 findings JSON 数组: " + root.getNodeType());
        }
        List<ModelFinding> findings = new ArrayList<>();
        int malformed = 0;
        for (JsonNode node : root) {
            String file = text(node, "file");
            String existingCode = text(node, "existing_code");
            if (file == null || file.isBlank() || existingCode == null || existingCode.isBlank()) {
                malformed++; // 缺定位锚点，本条无法工程映射
                continue;
            }
            findings.add(new ModelFinding(
                    file,
                    node.hasNonNull("line") && node.get("line").canConvertToInt()
                            ? node.get("line").asInt() : null,
                    existingCode,
                    defaulted(text(node, "rule"), "unspecified"),
                    defaulted(text(node, "severity"), "INFO"),
                    defaulted(text(node, "message"), "")));
        }
        return new ParseResult(findings, malformed);
    }

    /** 容忍 ```json 栅栏与前后絮语：截取首尾方括号区间 */
    private static String extractJsonArray(String content) {
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new ModelOutputParseException("模型输出中找不到 JSON 数组");
        }
        return content.substring(start, end + 1);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static String defaulted(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
