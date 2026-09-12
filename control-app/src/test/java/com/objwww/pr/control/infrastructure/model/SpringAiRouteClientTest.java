package com.objwww.pr.control.infrastructure.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.domain.ai.ModelCallFailure;
import com.objwww.pr.control.domain.ai.ModelRequest;
import com.objwww.pr.control.domain.ai.RouteCallOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R5/BA-120 终止信号分类（MC25/26）：HTTP 200 且 content 空——带推理/截断终止痕迹
 * （finish_reason=length 或 completion_tokens&gt;0）= 输出预算耗尽（OutputBudgetExhausted，
 * 同参不可重试）；纯空无痕迹 = PROTOCOL_ERROR（原语义不动）。
 * 背景实证：deepseek-v4-flash-0731 推理模式把 max_tokens=1000 烧在 reasoning_content →
 * content 空 → 原 EMPTY_CONTENT→PROTOCOL_ERROR 分类放大为同参重试风暴。
 */
class SpringAiRouteClientTest {

    private static SpringAiRouteClient clientReturning(ChatResponse scripted) {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return scripted;
            }
        };
        return new SpringAiRouteClient(model, "route-test", "model-test", new ObjectMapper());
    }

    /** 空正文 + 指定终止信号（finishReason/推理侧 completion tokens）的响应体 */
    private static ChatResponse emptyContentResponse(String finishReason, int completionTokens) {
        Generation generation = new Generation(
                new org.springframework.ai.chat.messages.AssistantMessage(""),
                ChatGenerationMetadata.builder().finishReason(finishReason).build());
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(100, completionTokens, 100 + completionTokens))
                .model("model-test")
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }

    private static ModelRequest request() {
        return new ModelRequest("prompt", 1000, Duration.ofSeconds(30));
    }

    @Test
    void 空content且finish_reason_length_输出预算耗尽() {
        RouteCallOutcome outcome = clientReturning(emptyContentResponse("LENGTH", 195))
                .complete(request(), Duration.ofSeconds(30));

        assertThat(((RouteCallOutcome.Failed) outcome).failure())
                .as("BA-120：推理烧穿预算 = 终止信号，非协议错")
                .isInstanceOf(ModelCallFailure.OutputBudgetExhausted.class);
    }

    @Test
    void 空content但completion_tokens大于0_推理痕迹_输出预算耗尽() {
        // 某些供应商推理烧 token 不回 LENGTH（finish=stop）——completionTokens>0 同判
        RouteCallOutcome outcome = clientReturning(emptyContentResponse("STOP", 300))
                .complete(request(), Duration.ofSeconds(30));

        assertThat(((RouteCallOutcome.Failed) outcome).failure())
                .isInstanceOf(ModelCallFailure.OutputBudgetExhausted.class);
    }

    @Test
    void 纯空content无推理痕迹_保持PROTOCOL_ERROR原语义() {
        RouteCallOutcome outcome = clientReturning(emptyContentResponse("STOP", 0))
                .complete(request(), Duration.ofSeconds(30));

        assertThat(((RouteCallOutcome.Failed) outcome).failure())
                .isInstanceOf(ModelCallFailure.ProtocolError.class);
    }

    @Test
    void 正常content_成功映射不变() {
        Generation generation = new Generation(
                new org.springframework.ai.chat.messages.AssistantMessage("{\"final\":{}}"),
                ChatGenerationMetadata.builder().finishReason("STOP").build());
        ChatResponse response = new ChatResponse(List.of(generation),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(100, 20, 120)).model("model-test").build());
        RouteCallOutcome outcome = clientReturning(response).complete(request(), Duration.ofSeconds(30));

        assertThat(outcome).isInstanceOf(RouteCallOutcome.Ok.class);
        assertThat(((RouteCallOutcome.Ok) outcome).content()).isEqualTo("{\"final\":{}}");
    }

    // ---------------- 错误体 code 提取（195 真机 R4 探针实证的三种 wire 形状） ----------------

    @Test
    void 错误体提取_litellm预算耗尽_code数字回显_取语义type() {
        // litellm key 预算耗尽实证：HTTP 429 + {"error":{"type":"budget_exceeded","code":"429"}}
        // ——code 是状态码数字回显（无判别力），语义在 error.type
        String body = "{\"error\":{\"message\":\"Budget has been exceeded! Current cost: "
                + "7.000000000000001e-06, Max budget: 1e-06\",\"type\":\"budget_exceeded\","
                + "\"param\":null,\"code\":\"429\"}}";

        assertThat(clientReturning(emptyContentResponse("STOP", 0)).extractErrorCode(body))
                .isEqualTo("budget_exceeded");
    }

    @Test
    void 错误体提取_OpenAI嵌套字符串code_原样保留() {
        String body = "{\"error\":{\"message\":\"You exceeded your current quota\","
                + "\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}";

        assertThat(clientReturning(emptyContentResponse("STOP", 0)).extractErrorCode(body))
                .isEqualTo("insufficient_quota");
    }

    @Test
    void 错误体提取_百炼顶层code_原样保留() {
        assertThat(clientReturning(emptyContentResponse("STOP", 0))
                .extractErrorCode("{\"code\":\"Throttling\",\"message\":\"请求过快\"}"))
                .isEqualTo("Throttling");
    }

    @Test
    void 错误体提取_数字code且无type_数字原样返回_failClosed面不变() {
        assertThat(clientReturning(emptyContentResponse("STOP", 0))
                .extractErrorCode("{\"error\":{\"code\":\"429\"}}"))
                .isEqualTo("429");
    }
}
