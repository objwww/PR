package com.objwww.pr.control.alert.application;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * L0 入口注入扫描器（PA-A3，矩阵 L0-4/L0-5；位于 schema 校验之后、落库之前）。
 *
 * <p>扫描面 = 将来可能进入模型上下文的字符串值：groupLabels/commonLabels/
 * commonAnnotations 与各 alert 的 labels/annotations。特征为本地保守清单
 * （与 ToolRisk 不信任外部注解同一原则——清单不出自告警载荷自身），大小写不敏感
 * 子串匹配；命中 = 建议隔离（QUARANTINED），由调用方裁决（intake 侧 fail-safe：
 * 命中不落 RECEIVED、不进业务处理，人工审核后经 QUARANTINED→RECEIVED 放行边重驱）。
 *
 * <p>清单是"疑似注入特征"不是判决——保守起见宁可误隔离（误隔离成本 = 人工看一眼；
 * 漏隔离成本 = 告警内容进入模型上下文）。
 */
public final class AlertInjectionScanner {

    /** 本地特征清单（小写存储，子串匹配；新增特征须过误报评审并登记台账） */
    public static final List<String> DEFAULT_PATTERNS = List.of(
            "ignore previous", "ignore all previous", "ignore the above",
            "disregard previous", "disregard the above", "disregard your instructions",
            "forget your instructions", "forget your previous",
            "system prompt", "you are now", "act as the system", "act as system",
            "developer mode", "<|im_start|>", "<|endoftext|>", "<|system|>",
            "忽略之前", "忽略以上", "忽略前面", "忽略之前的", "你现在是", "扮演系统", "系统提示词");

    /** 扫描结论：infected = 命中至少一特征；patterns = 命中的特征原文（审计用） */
    public record Result(boolean infected, List<String> hitPatterns) {
        static final Result CLEAN = new Result(false, List.of());
    }

    private final List<String> patterns;

    public AlertInjectionScanner() {
        this(DEFAULT_PATTERNS);
    }

    public AlertInjectionScanner(List<String> patterns) {
        this.patterns = List.copyOf(patterns);
    }

    public Result scan(JsonNode root) {
        List<String> values = new ArrayList<>();
        collectStrings(root.get("groupLabels"), values);
        collectStrings(root.get("commonLabels"), values);
        collectStrings(root.get("commonAnnotations"), values);
        JsonNode alerts = root.get("alerts");
        if (alerts != null && alerts.isArray()) {
            for (JsonNode alert : alerts) {
                collectStrings(alert.get("labels"), values);
                collectStrings(alert.get("annotations"), values);
            }
        }
        List<String> hits = new ArrayList<>();
        for (String value : values) {
            String lower = value.toLowerCase(Locale.ROOT);
            for (String pattern : patterns) {
                if (lower.contains(pattern)) {
                    if (!hits.contains(pattern)) {
                        hits.add(pattern);
                    }
                }
            }
        }
        return hits.isEmpty() ? Result.CLEAN : new Result(true, List.copyOf(hits));
    }

    private static void collectStrings(JsonNode node, List<String> out) {
        if (node == null || !node.isObject()) {
            return;
        }
        for (java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = node.fields();
                it.hasNext(); ) {
            JsonNode value = it.next().getValue();
            if (value.isTextual() && !value.asText().isBlank()) {
                out.add(value.asText());
            }
        }
    }
}
