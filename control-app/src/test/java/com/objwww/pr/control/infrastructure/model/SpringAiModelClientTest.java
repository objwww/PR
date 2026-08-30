package com.objwww.pr.control.infrastructure.model;

import com.objwww.pr.control.domain.ai.ModelBudgetExceededException;
import com.objwww.pr.control.domain.ai.ModelBudgetGuard;
import com.objwww.pr.control.domain.ai.ModelRequest;
import com.objwww.pr.control.domain.ai.ModelResult;
import com.objwww.pr.control.domain.ai.ModelTimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SpringAiModelClient 单元测试：结果映射、预算两道闸、硬超时。OpenAiChatModel 用 Mockito 替身。
 */
class SpringAiModelClientTest {

    private final OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
    private final SpringAiModelClient client =
            new SpringAiModelClient(chatModel, new ModelBudgetGuard(1_000), "qwen-plus");

    private static ChatResponse response(String content, int prompt, int completion, String model) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))),
                ChatResponseMetadata.builder()
                        .model(model)
                        .usage(new DefaultUsage(prompt, completion, prompt + completion))
                        .build());
    }

    @Test
    void mapsContentUsageAndActualModel() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(response("{\"findings\":[]}", 120, 30, "qwen-plus-2025-01"));

        ModelResult result = client.complete(
                new ModelRequest("review this diff", 500, Duration.ofSeconds(30)));

        assertEquals("{\"findings\":[]}", result.content());
        assertEquals(120, result.tokenUsage().promptTokens());
        assertEquals(30, result.tokenUsage().completionTokens());
        assertEquals(150, result.tokenUsage().totalTokens());
        assertEquals("qwen-plus-2025-01", result.actualModel());
    }

    @Test
    void passesMaxTokensBudgetToModelOptions() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("ok", 1, 1, null));

        client.complete(new ModelRequest("p", 500, Duration.ofSeconds(30)));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        assertEquals(500, ((OpenAiChatOptions) captor.getValue().getOptions()).getMaxTokens());
    }

    @Test
    void fallsBackToConfiguredModelWhenMetadataHasNoModel() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("ok", 1, 1, null));
        assertEquals("qwen-plus",
                client.complete(new ModelRequest("p", 100, Duration.ofSeconds(30))).actualModel());
    }

    @Test
    void rejectsRequestOverHardCapBeforeCallingModel() {
        assertThrows(ModelBudgetExceededException.class,
                () -> client.complete(new ModelRequest("p", 1_001, Duration.ofSeconds(30))));
        verify(chatModel, never()).call(any(Prompt.class)); // 预算违约不触网
    }

    @Test
    void rejectsActualCompletionOverBudget() {
        // 请求预算 100，实际 completion 150 → 违约（结果应被丢弃，Step FAILED）
        when(chatModel.call(any(Prompt.class))).thenReturn(response("ok", 10, 150, "m"));
        assertThrows(ModelBudgetExceededException.class,
                () -> client.complete(new ModelRequest("p", 100, Duration.ofSeconds(30))));
    }

    @Test
    void timesOutSlowCalls() {
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(5_000);
            return response("late", 1, 1, "m");
        });
        assertThrows(ModelTimeoutException.class,
                () -> client.complete(new ModelRequest("p", 100, Duration.ofMillis(100))));
    }
}
