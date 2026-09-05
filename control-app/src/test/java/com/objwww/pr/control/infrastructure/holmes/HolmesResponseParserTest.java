package com.objwww.pr.control.infrastructure.holmes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.Digests;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3-05：Holmes 外层响应 Parser 穷举单测——tool_calls 形状解析、call_id 兼容、
 * 摘要纪律（params/result 只落 SHA-256）、条数上限、thought 不读（FUT-09）。
 */
class HolmesResponseParserTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HolmesResponseParser parser = new HolmesResponseParser(3);

    private String bodyWith(ObjectNode... toolCalls) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("analysis", "结论文本");
        ArrayNode calls = root.putArray("tool_calls");
        for (ObjectNode call : toolCalls) {
            calls.add(call);
        }
        return mapper.writeValueAsString(root);
    }

    private ObjectNode call(String name, String status) {
        ObjectNode call = mapper.createObjectNode();
        call.put("call_id", "call-" + name);
        call.put("name", name);
        if (status != null) {
            call.put("status", status);
        }
        call.putObject("params").put("query", "up");
        call.put("result", "res-value");
        return call;
    }

    @Test
    @DisplayName("正常解析：analysis + tool_calls 全字段（seq 从 1 起，params/result 成摘要）")
    void parsesAnalysisAndToolCalls() throws Exception {
        HolmesResponseParser.Parsed parsed = parser.parse(
                bodyWith(call("prometheus_query", "success"), call("k8s_describe", "error")));

        assertThat(parsed.analysis()).isEqualTo("结论文本");
        assertThat(parsed.toolCalls()).hasSize(2);
        HolmesResponseParser.RawToolCall first = parsed.toolCalls().get(0);
        assertThat(first.callId()).isEqualTo("call-prometheus_query");
        assertThat(first.sequenceNo()).isEqualTo(1);
        assertThat(first.toolName()).isEqualTo("prometheus_query");
        assertThat(first.status()).isEqualTo("success");
        assertThat(first.paramsDigest()).isEqualTo(Digest.sha256Of(mapper.createObjectNode()
                .put("query", "up").toString()));
        assertThat(first.resultDigest()).isEqualTo(Digest.sha256Of("res-value"));
        assertThat(parsed.toolCalls().get(1).sequenceNo()).isEqualTo(2);
    }

    @Test
    @DisplayName("call_id 缺失时兼容 id 键；两者都缺合成 tc-<seq>")
    void callIdFallbacks() throws Exception {
        ObjectNode withId = call("a", null);
        withId.remove("call_id");
        withId.put("id", "alt-id");
        ObjectNode withNeither = call("b", null);
        withNeither.remove("call_id");

        HolmesResponseParser.Parsed parsed = parser.parse(bodyWith(withId, withNeither));

        assertThat(parsed.toolCalls().get(0).callId()).isEqualTo("alt-id");
        assertThat(parsed.toolCalls().get(1).callId()).isEqualTo("tc-2");
    }

    @Test
    @DisplayName("params 缺失 / result 非文本 → digest 为 null（不猜测）")
    void missingParamsAndNonTextResultGiveNullDigests() throws Exception {
        ObjectNode minimal = mapper.createObjectNode();
        minimal.put("name", "bare_tool");

        HolmesResponseParser.Parsed parsed = parser.parse(bodyWith(minimal));

        assertThat(parsed.toolCalls()).hasSize(1);
        assertThat(parsed.toolCalls().get(0).paramsDigest()).isNull();
        assertThat(parsed.toolCalls().get(0).resultDigest()).isNull();
        assertThat(parsed.toolCalls().get(0).status()).isNull();
    }

    @Test
    @DisplayName("FUT-09：条目中的 thought/推理字段不读不存（解析不受影响）")
    void thoughtFieldsAreIgnored() throws Exception {
        ObjectNode withThought = call("prometheus_query", "success");
        withThought.put("thought", "内部推理链，绝不落库");

        HolmesResponseParser.Parsed parsed = parser.parse(bodyWith(withThought));

        assertThat(parsed.toolCalls()).hasSize(1);
        assertThat(parsed.toolCalls().get(0).toString()).doesNotContain("内部推理链");
    }

    @Test
    @DisplayName("tool_calls 缺席 → 空列表（无工具调用的调查同样合法）")
    void missingToolCallsYieldsEmptyList() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("analysis", "直接结论");

        assertThat(parser.parse(mapper.writeValueAsString(root)).toolCalls()).isEmpty();
    }

    @Test
    @DisplayName("拒绝形态：缺 analysis / tool_calls 非数组 / 条目缺 name")
    void malformedShapesAreRejected() throws Exception {
        assertThatThrownBy(() -> parser.parse("{\"tool_calls\":[]}"))
                .isInstanceOf(HolmesResponseParser.MalformedResponseException.class);
        assertThatThrownBy(() -> parser.parse("{\"analysis\":\"x\",\"tool_calls\":{}}"))
                .isInstanceOf(HolmesResponseParser.MalformedResponseException.class);
        assertThatThrownBy(() -> parser.parse(
                "{\"analysis\":\"x\",\"tool_calls\":[{\"call_id\":\"c\"}]}"))
                .isInstanceOf(HolmesResponseParser.MalformedResponseException.class);
        assertThatThrownBy(() -> parser.parse("not json at all"))
                .isInstanceOf(HolmesResponseParser.MalformedResponseException.class);
        assertThatThrownBy(() -> parser.parse(""))
                .isInstanceOf(HolmesResponseParser.MalformedResponseException.class);
    }

    @Test
    @DisplayName("条数超上限：整体拒绝（异常形态同 REJECTED_MALFORMED 待遇）")
    void exceedingMaxToolCallsRejected() throws Exception {
        String body = bodyWith(call("t1", null), call("t2", null),
                call("t3", null), call("t4", null));

        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(HolmesResponseParser.MalformedResponseException.class)
                .hasMessageContaining("4").hasMessageContaining("3");
    }

    @Test
    @DisplayName("摘要与原文可对账：params 序列化文本的 SHA-256 可复算")
    void digestIsVerifiableAgainstOriginalText() throws Exception {
        HolmesResponseParser.Parsed parsed = parser.parse(bodyWith(call("t", null)));

        ObjectNode params = mapper.createObjectNode().put("query", "up");
        assertThat(Digests.sha256Hex(params.toString()))
                .isEqualTo(parsed.toolCalls().get(0).paramsDigest().value());
    }
}
