package com.objwww.pr.control.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.repository.EvalReportJudge;
import org.springframework.http.MediaType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link EvalReportJudge} 的 OpenAI 兼容实现（P6-G7）：POST /v1/chat/completions
 * ——195 走 litellm-am3 代理（与主链同一模型面，eval worker 经 alert-net 可达）。
 *
 * <p>rubric v1（美团二元化）：三道是/否题只判报告自然语言质量维——结论明确性、
 * 自洽性、可操作性；verdict 口径：全是=PASS，否则 FAIL。模型只输出严格 JSON，
 * 解析失败上抛（调用方折算 ERROR 行）。
 *
 * <p>fail-closed：base-url 或 api-key 缺席 → judge() 返回 empty（未启用如实缺席，
 * 不落行不冒充）；超时 30s。
 */
public class HttpEvalReportJudge implements EvalReportJudge {

    public static final String RUBRIC_VERSION = "judge-rubric-v1";

    /** rubric v1 题面（二元；版本冻结——改题面必须升版本号，校准一致率才有锚） */
    private static final List<String> QUESTIONS = List.of(
            "Q1 结论明确性：报告是否明确陈述了结论，或在无法定因时明确说明了原因？（是/否）",
            "Q2 自洽性：报告的结论与其正文/证据陈述是否自洽、无互相矛盾？（是/否）",
            "Q3 可操作性：报告是否给出下一步排查方向或修复建议？（是/否）");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public HttpEvalReportJudge(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = Objects.requireNonNullElse(model, "qwen3-max");
    }

    @Override
    public Optional<JudgeOutcome> judge(String reportText) {
        if (baseUrl.isEmpty() || apiKey.isEmpty() || reportText == null
                || reportText.isBlank()) {
            return Optional.empty();
        }
        try {
            String content = callModel(truncate(reportText));
            return Optional.of(parse(content));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("judge 调用被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("judge 调用失败: " + e.getMessage(), e);
        }
    }

    /** 单次 chat 补全（temperature=0 裁决确定性面；max_tokens 须覆盖 reasoning 消耗——
     *  deepseek 系端点先产 reasoning_content 再产 content，300 会被推理段吃光导致
     *  content 空串（195 实测 63/63 全灭根因），2048 对三题 JSON 有余） */
    private String callModel(String reportText) throws Exception {
        String system = "你是告警根因报告的评测裁判。只依据给定的报告原文回答三道是否题，"
                + "不引入报告之外的知识。只输出 JSON："
                + "{\"answers\":[{\"id\":\"Q1\",\"yes\":true|false},"
                + "{\"id\":\"Q2\",\"yes\":true|false},{\"id\":\"Q3\",\"yes\":true|false}]}";
        StringBuilder user = new StringBuilder("报告原文：\n").append(reportText)
                .append("\n\n题目：\n");
        for (String q : QUESTIONS) {
            user.append(q).append('\n');
        }
        String body = JSON.writeValueAsString(java.util.Map.of(
                "model", model,
                "messages", List.of(
                        java.util.Map.of("role", "system", "content", system),
                        java.util.Map.of("role", "user", "content", user.toString())),
                "temperature", 0,
                "max_tokens", 2048));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("judge http=" + response.statusCode()
                    + " body=" + abbreviate(response.body()));
        }
        JsonNode root = JSON.readTree(response.body());
        return root.path("choices").path(0).path("message").path("content").asText();
    }

    /** 严格 JSON 解析（三题齐全才算数；缺题/多余题/非法答案均拒绝——不猜） */
    JudgeOutcome parse(String content) throws Exception {
        String json = content.trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("judge 输出无 JSON: " + abbreviate(content));
        }
        JsonNode answers = JSON.readTree(json.substring(start, end + 1)).path("answers");
        if (!answers.isArray() || answers.size() != QUESTIONS.size()) {
            throw new IllegalStateException("judge answers 题数不符: " + answers.size());
        }
        List<Answer> out = new ArrayList<>();
        for (int i = 0; i < QUESTIONS.size(); i++) {
            JsonNode a = answers.get(i);
            String id = a.path("id").asText();
            if (!QUESTIONS.get(i).startsWith(id)) {
                throw new IllegalStateException("judge 题序错位: " + id);
            }
            JsonNode yes = a.get("yes");
            if (yes == null || !yes.isBoolean()) {
                throw new IllegalStateException("judge 答案非布尔: " + id);
            }
            out.add(new Answer(id, QUESTIONS.get(i), yes.asBoolean()));
        }
        long passed = out.stream().filter(Answer::yes).count();
        return new JudgeOutcome(RUBRIC_VERSION, model, List.copyOf(out),
                (int) passed, out.size());
    }

    private static String truncate(String text) {
        return text.length() <= 4000 ? text : text.substring(0, 4000);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 160 ? s : s.substring(0, 160);
    }
}
