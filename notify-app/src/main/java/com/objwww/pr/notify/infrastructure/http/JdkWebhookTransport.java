package com.objwww.pr.notify.infrastructure.http;

import com.objwww.pr.notify.domain.channel.WebhookTransport;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * JDK HttpClient 实现（连接超时 5s / 请求超时 10s；异常分类在 Classifier 纯函数）。
 * webhook URL 仅运行时内存持有，不落任何日志与持久化面（INV-AM3-3）。
 */
@Component
public final class JdkWebhookTransport implements WebhookTransport {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public Response post(String url, String jsonBody) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody,
                                    StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(),
                    response.headers().firstValue("Retry-After").orElse(null),
                    response.body());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new TransportFailure(e);
        }
    }
}
