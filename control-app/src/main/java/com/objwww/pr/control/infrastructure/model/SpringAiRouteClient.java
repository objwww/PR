package com.objwww.pr.control.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.domain.ai.ModelRequest;
import com.objwww.pr.control.domain.ai.RouteCallOutcome;
import com.objwww.pr.control.domain.ai.RouteClientPort;
import com.objwww.pr.control.domain.ai.TokenUsage;
import com.objwww.pr.shared.RetryAfterParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Spring AI 单路由适配器（§3.1/附录 D 触点 10）：实现 RouteClientPort，每 route 一个实例
 * （手工装配，T00）。
 *
 * <p>硬约束：
 * <ul>
 *   <li>I34：一次 complete() 至多一次真实 HTTP——隐藏重试由装配处关闭
 *       （spring.ai.retry.max-attempts=1 + 手工 RetryTemplate maxAttempts(1)）；</li>
 *   <li>故障分类只认原始 HTTP status/headers/body（{@link RawHttpErrorCapture} 在错误链
 *       最底层捕获，F-17：不认 Spring AI 异常类型）；</li>
 *   <li>超时两层：领域超时 Future.get(perCallTimeout) 先触发（给分类与账本用），
 *       socket 兜底由底层 client 的 connect/read timeout 释放（F-18：cancel(true) 杀不掉
 *       JDK HttpClient 在途请求，中断只是"放手"）；</li>
 *   <li>§4.11：供应商异常原文/body 不进日志——日志只记异常类名与状态码。</li>
 * </ul>
 */
public class SpringAiRouteClient implements RouteClientPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiRouteClient.class);

    private final ChatModel chatModel;
    private final String routeId;
    private final String requestedModel;
    private final ProviderErrorClassifier classifier;
    private final ObjectMapper objectMapper;

    public SpringAiRouteClient(ChatModel chatModel, String routeId, String requestedModel,
                               ObjectMapper objectMapper) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.routeId = Objects.requireNonNull(routeId, "routeId");
        this.requestedModel = Objects.requireNonNull(requestedModel, "requestedModel");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.classifier = new ProviderErrorClassifier();
    }

    @Override
    public RouteCallOutcome complete(ModelRequest request, Duration perCallTimeout) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(perCallTimeout, "perCallTimeout");

        // max-completion-tokens-per-call 下发为请求参数（§4.4）
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(requestedModel)
                .maxTokens(request.maxTokens())
                .build();
        Prompt prompt = new Prompt(request.prompt(), options);

        Instant callStart = Instant.now();
        // 虚拟线程 + Future.get 领域超时（M0 既有形态；socket 兜底在底层 client 配置）
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<ChatResponse> future = executor.submit(() -> chatModel.call(prompt));
            ChatResponse response = future.get(perCallTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return mapSuccess(response, Duration.between(callStart, Instant.now()));
        } catch (TimeoutException e) {
            // A11：本地超时——不原地重试；底层 socket 由 read-timeout 兜底释放
            Duration latency = Duration.between(callStart, Instant.now());
            log.warn("route={} 本地超时 {}ms", routeId, latency.toMillis());
            return new RouteCallOutcome.Failed(classifier.classifyTimeout(), null, null, null, latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Duration latency = Duration.between(callStart, Instant.now());
            log.warn("route={} 调用被中断", routeId);
            return new RouteCallOutcome.Failed(classifier.classifyTimeout(), null, null, null, latency);
        } catch (ExecutionException e) {
            Duration latency = Duration.between(callStart, Instant.now());
            return classifyFailure(e.getCause(), latency);
        } finally {
            executor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 成功映射

    private RouteCallOutcome mapSuccess(ChatResponse response, Duration latency) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            log.warn("route={} 响应结构缺失（response/result/output 为 null）", routeId);
            return new RouteCallOutcome.Failed(
                    classifier.classifyProtocolError("NULL_RESPONSE_STRUCTURE"), null, null, null, latency);
        }
        String content = response.getResult().getOutput().getText();
        if (content == null || content.isEmpty()) {
            log.warn("route={} 返回空 content", routeId);
            return new RouteCallOutcome.Failed(
                    classifier.classifyProtocolError("EMPTY_CONTENT"), null, null, null, latency);
        }

        ChatResponseMetadata metadata = response.getMetadata();
        Usage springUsage = metadata == null ? null : metadata.getUsage();
        boolean usageMissing = springUsage == null;
        TokenUsage usage = usageMissing ? new TokenUsage(0, 0, 0) : new TokenUsage(
                springUsage.getPromptTokens() == null ? 0 : springUsage.getPromptTokens().longValue(),
                springUsage.getCompletionTokens() == null ? 0 : springUsage.getCompletionTokens().longValue(),
                springUsage.getTotalTokens() == null ? 0 : springUsage.getTotalTokens().longValue());
        String reportedModel = metadata == null ? null : metadata.getModel();

        return new RouteCallOutcome.Ok(content, usage, usageMissing, reportedModel, null, latency);
    }

    // ------------------------------------------------------------------ 失败分类（只认原始 HTTP 事实）

    private RouteCallOutcome.Failed classifyFailure(Throwable cause, Duration latency) {
        // 原始 HTTP 错误（RawHttpErrorCapture 在错误链最底层捕获，穿透 Spring AI 包装）
        RawHttpErrorException raw = findCause(cause, RawHttpErrorException.class);
        if (raw != null) {
            String retryAfterHeader = raw.headers() == null ? null : raw.headers().getFirst("Retry-After");
            Long retryAfterSeconds = RetryAfterParser.parseSeconds(retryAfterHeader, Instant.now());
            Duration retryAfter = retryAfterSeconds == null ? null : Duration.ofSeconds(retryAfterSeconds);
            String errorCode = extractErrorCode(raw.body());
            log.warn("route={} HTTP {} errorCode={}", routeId, raw.status(),
                    errorCode == null ? "<none>" : errorCode);
            return new RouteCallOutcome.Failed(
                    classifier.classify(raw.status(), errorCode, retryAfter),
                    raw.status(), retryAfter, errorCode, latency);
        }

        // socket 读超时（底层 read-timeout 兜底触发）→ 本地超时语义（A11）
        if (findCause(cause, SocketTimeoutException.class) != null) {
            log.warn("route={} socket 读超时", routeId);
            return new RouteCallOutcome.Failed(classifier.classifyTimeout(), null, null, null, latency);
        }

        // 连接层失败（DNS/连接拒绝/TLS/中途 reset）→ NetworkError（A12）
        if (findCause(cause, ConnectException.class) != null
                || findCause(cause, UnknownHostException.class) != null
                || findCause(cause, javax.net.ssl.SSLException.class) != null
                || cause instanceof org.springframework.web.client.ResourceAccessException) {
            log.warn("route={} 网络错误: {}", routeId, cause.getClass().getSimpleName());
            return new RouteCallOutcome.Failed(
                    classifier.classifyNetworkError(cause.getClass().getSimpleName()),
                    null, null, null, latency);
        }

        // fail-closed：未知异常不泄露原文（§4.11），只记类名
        log.warn("route={} 未知异常: {}", routeId, cause.getClass().getSimpleName());
        return new RouteCallOutcome.Failed(
                classifier.classifyUnknown(cause.getClass().getSimpleName()), null, null, null, latency);
    }

    /**
     * 百炼错误体双结构解析（§4.2）：OpenAI 兼容嵌套 {@code {"error":{"code",...}}}
     * + DashScope 原生顶层 {@code {"code",...}}；不做单一结构假设；解析失败返回 null
     * （code 缺失走 fail-closed 分类）。
     */
    private String extractErrorCode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode nested = root.path("error").path("code");
            if (nested.isTextual()) {
                return nested.asText();
            }
            JsonNode top = root.path("code");
            if (top.isTextual()) {
                return top.asText();
            }
            return null;
        } catch (Exception e) {
            return null; // 非 JSON 错误体（网关 HTML 等）→ code 缺失
        }
    }

    private static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        while (t != null) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            t = t.getCause();
        }
        return null;
    }
}
