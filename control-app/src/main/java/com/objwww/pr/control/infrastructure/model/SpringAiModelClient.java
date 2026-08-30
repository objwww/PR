package com.objwww.pr.control.infrastructure.model;

import com.objwww.pr.control.domain.ai.ModelBudgetGuard;
import com.objwww.pr.control.domain.ai.ModelClient;
import com.objwww.pr.control.domain.ai.ModelRequest;
import com.objwww.pr.control.domain.ai.ModelResult;
import com.objwww.pr.control.domain.ai.ModelTimeoutException;
import com.objwww.pr.control.domain.ai.TokenUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Spring AI OpenAI 兼容端点适配器（§6.6：Spring AI 仅作适配器，供应商概念不进 domain）。
 * 端点与模型经配置注入（base-url / api-key / model，见 application-docker.yml），
 * 本类只面向 Spring AI 的 ChatModel 抽象。
 *
 * <p>两道硬约束：token 预算（调用前后各一道，{@link ModelBudgetGuard}）与单次调用硬超时
 * （虚拟线程 + Future.get(timeout)，超时即 {@link ModelTimeoutException}，Step FAILED 不降级）。
 *
 * <p>刻意不加 Spring 注解：默认 profile 空跑不装配，接线（@Configuration）属后续任务。
 */
public class SpringAiModelClient implements ModelClient {

    private final OpenAiChatModel chatModel;
    private final ModelBudgetGuard budgetGuard;
    private final String configuredModel;

    public SpringAiModelClient(OpenAiChatModel chatModel, ModelBudgetGuard budgetGuard,
                               String configuredModel) {
        this.chatModel = Objects.requireNonNull(chatModel);
        this.budgetGuard = Objects.requireNonNull(budgetGuard);
        this.configuredModel = Objects.requireNonNull(configuredModel);
    }

    @Override
    public ModelResult complete(ModelRequest request) {
        Objects.requireNonNull(request, "request");
        budgetGuard.validate(request); // ① 调用前：申请预算不得超硬上限

        Prompt prompt = new Prompt(request.prompt(),
                OpenAiChatOptions.builder().maxTokens(request.maxTokens()).build());
        ChatResponse response = callWithTimeout(prompt, request.timeout());
        ModelResult result = map(request, response);

        budgetGuard.checkUsage(request, result.tokenUsage()); // ② 调用后：实际用量不得超预算
        return result;
    }

    /** 阻塞调用套上硬超时：超时即中断底层调用（虚拟线程 + cancel(true)），抛领域异常 */
    private ChatResponse callWithTimeout(Prompt prompt, Duration timeout) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<ChatResponse> future = executor.submit(() -> chatModel.call(prompt));
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ModelTimeoutException("模型调用超过硬超时 " + timeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelTimeoutException("模型调用被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("模型调用失败", cause);
        } finally {
            executor.shutdownNow(); // 超时/异常路径中断底层 HTTP 读，不挂着等
        }
    }

    /** Spring AI 响应 → 领域结果（actualModel 以响应元数据为准，缺省回落配置值） */
    private ModelResult map(ModelRequest request, ChatResponse response) {
        Generation generation = response.getResult();
        AssistantMessage output = generation == null ? null : generation.getOutput();
        String content = output == null || output.getText() == null ? "" : output.getText();

        ChatResponseMetadata metadata = response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        TokenUsage tokenUsage = new TokenUsage(
                usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens(),
                usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens(),
                usage == null || usage.getTotalTokens() == null ? 0 : usage.getTotalTokens());

        String actualModel = metadata != null && metadata.getModel() != null
                ? metadata.getModel() : configuredModel;
        return new ModelResult(content, tokenUsage, actualModel);
    }
}
