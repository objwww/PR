package com.objwww.pr.control.infrastructure.holmes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.shared.Digest;

import java.util.ArrayList;
import java.util.List;

/**
 * Holmes 外层响应 Parser（M3-05）：解析 analysis 字段与 tool_calls 数组。
 *
 * <p>分工边界：usage 解析留在 {@link HolmesClient}（账本单一来源，不重复建一套）；
 * 响应超限由受限读 + EvidencePackageValidator 的 REJECTED_OVERSIZE 决策；本类只管
 * tool_calls 形状解析与条数上限（超限 = 恶意/异常形态，REJECTED_MALFORMED 同待遇）。
 *
 * <p>脱敏纪律：params/result 只落 SHA-256 摘要，原文不落库（M3-27 才落 CAS）；
 * FUT-09——条目中的 thought/推理字段一律不读不存。
 *
 * <p>未知字段兼容：只读已知键，多余键忽略；call_id 兼容 call_id/id 两种键名，
 * 都缺时合成 "tc-&lt;seq&gt;"（不因元数据形状拒绝一次成功的调查）。
 */
public final class HolmesResponseParser {

    /** tool_calls 条目上限（资源上限；超过即视为异常形态整体拒绝） */
    private final int maxToolCalls;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 解析后的单条工具调用（M3-06 由 ToolCallAdapter 转内部契约） */
    public record RawToolCall(String callId, int sequenceNo, String toolName, String status,
                              Digest paramsDigest, Digest resultDigest) {
    }

    /** 外层解析结果：analysis 原文（不解析内嵌 JSON——那是 Validator 的链）+ tool_calls */
    public record Parsed(String analysis, List<RawToolCall> toolCalls) {
    }

    /** 外层形状违规（缺 analysis / tool_calls 非数组 / 条数超限） */
    public static final class MalformedResponseException extends Exception {
        MalformedResponseException(String message) {
            super(message);
        }
    }

    public HolmesResponseParser(int maxToolCalls) {
        if (maxToolCalls < 1) {
            throw new IllegalArgumentException("maxToolCalls 从 1 起");
        }
        this.maxToolCalls = maxToolCalls;
    }

    public Parsed parse(String body) throws MalformedResponseException {
        if (body == null || body.isEmpty()) {
            throw new MalformedResponseException("Holmes 响应为空");
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            throw new MalformedResponseException("JSON 解析失败: " + e.getMessage());
        }
        if (!root.isObject() || !root.has("analysis") || !root.get("analysis").isTextual()) {
            throw new MalformedResponseException("Holmes 外层响应缺 analysis 字符串字段");
        }
        return new Parsed(root.get("analysis").asText(), parseToolCalls(root));
    }

    private List<RawToolCall> parseToolCalls(JsonNode root) throws MalformedResponseException {
        JsonNode calls = root.get("tool_calls");
        if (calls == null || calls.isNull()) {
            return List.of();
        }
        if (!calls.isArray()) {
            throw new MalformedResponseException("tool_calls 必须为数组");
        }
        if (calls.size() > maxToolCalls) {
            throw new MalformedResponseException(
                    "tool_calls 条数 " + calls.size() + " 超上限 " + maxToolCalls);
        }
        List<RawToolCall> out = new ArrayList<>();
        int seq = 0;
        for (JsonNode call : calls) {
            seq++;
            if (!call.isObject() || !call.has("name") || !call.get("name").isTextual()) {
                throw new MalformedResponseException("tool_call 条目缺 name 字符串字段");
            }
            JsonNode params = call.get("params");
            JsonNode result = call.get("result");
            out.add(new RawToolCall(
                    callId(call, seq),
                    seq,
                    call.get("name").asText(),
                    call.has("status") && call.get("status").isTextual()
                            ? call.get("status").asText() : null,
                    params == null || params.isMissingNode() || params.isNull()
                            ? null : digestOf(params),
                    result == null || !result.isTextual() ? null : Digest.sha256Of(result.asText())));
        }
        return List.copyOf(out);
    }

    private static String callId(JsonNode call, int seq) {
        JsonNode id = call.get("call_id");
        if (id == null || !id.isTextual()) {
            id = call.get("id");
        }
        return id != null && id.isTextual() && !id.asText().isBlank()
                ? id.asText() : "tc-" + seq;
    }

    /** params 结构化序列化后摘要（键序固定——Jackson ObjectNode 保持写入序，模型侧同源） */
    private Digest digestOf(JsonNode params) {
        return Digest.sha256Of(params.toString());
    }
}
