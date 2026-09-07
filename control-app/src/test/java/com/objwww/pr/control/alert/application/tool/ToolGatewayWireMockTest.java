package com.objwww.pr.control.alert.application.tool;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;
import com.objwww.pr.control.alert.domain.tool.ToolPolicy;
import com.objwww.pr.control.alert.domain.tool.ToolRisk;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * ToolGateway × WireMock 真网络面（AM4 M4-17 任务行指定）：timeout/oversize/取消
 * （迟到结果作废）在真 HTTP 语义下复证；错误两族映射不变。
 */
class ToolGatewayWireMockTest {

    private static final WireMockServer WIREMOCK = new WireMockServer(0);
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    @AfterAll
    static void tearDown() {
        WIREMOCK.stop();
        POOL.shutdownNow();
    }

    private static String baseUrl() {
        if (!WIREMOCK.isRunning()) {
            WIREMOCK.start();
        }
        return WIREMOCK.baseUrl();
    }

    /** 真 HTTP 执行器（GET 固定端点；自身请求超时 = deadline 剩余，异常交 Gateway 映射） */
    private static final class HttpToolExecutor implements ToolExecutor {
        private final String url;

        HttpToolExecutor(String url) {
            this.url = url;
        }

        @Override
        public byte[] execute(ToolExecution execution) throws Exception {
            long remaining = execution.deadlineEpochMillis() - System.currentTimeMillis();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(1, remaining)))
                    .GET()
                    .build();
            HttpResponse<byte[]> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new java.io.IOException("HTTP " + response.statusCode());
            }
            return response.body();
        }
    }

    private static ToolGateway gatewayFor(String url, long timeoutMillis, long resultLimit) {
        ToolDefinition definition = new ToolDefinition("prom.query", "1.0.0",
                Map.of("type", "object",
                        "properties", Map.of("q", Map.of("type", "string"))),
                ToolRisk.R0, timeoutMillis, resultLimit);
        return new ToolGateway(
                new ToolRegistry(List.of(
                        new ToolRegistry.Registration(definition,
                                new HttpToolExecutor(url)))),
                new ToolPolicy(Set.of("prom.query")), POOL, Clock.systemUTC(), null);
    }

    private static ToolGateway.ToolInvocation invocation() {
        return new ToolGateway.ToolInvocation(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "prom.query", "1.0.0",
                "2026-09-07T00:00:00Z/2026-09-07T01:00:00Z", Map.of("q", "up"), null);
    }

    @Test
    void itW01_正常响应_EXECUTED_字节面完整() {
        WIREMOCK.stubFor(get(anyUrl()).willReturn(aResponse()
                .withStatus(200).withBody("{\"status\":\"success\",\"value\":[1,2,3]}")));
        ToolGateway gateway = gatewayFor(baseUrl(), 5_000, 1_024);
        ToolGateway.ToolInvocationResult result = gateway.invoke(invocation());
        assertThat(result.kind()).isEqualTo(ToolGateway.ToolInvocationResult.Kind.EXECUTED);
        assertThat(new String(result.body())).contains("success");
    }

    @Test
    void itW02_真网络超时_硬deadline作废迟到结果_可重试族() {
        WIREMOCK.stubFor(get(anyUrl()).willReturn(aResponse()
                .withStatus(200).withFixedDelay(5_000).withBody("late")));
        long start = System.currentTimeMillis();
        ToolGateway gateway = gatewayFor(baseUrl(), 300, 1_024);
        assertThatThrownBy(() -> gateway.invoke(invocation()))
                .isInstanceOfSatisfying(ToolModelVisibleException.class,
                        e -> assertThat(e.reason())
                                .isEqualTo(ToolModelVisibleReason.TIMEOUT_RETRYABLE));
        // 硬 deadline 生效：不等 5s 迟到结果（取消语义）
        assertThat(System.currentTimeMillis() - start).isLessThan(4_000);
    }

    @Test
    void itW03_响应超结果上限_RESULT_OVERSIZE_终止族() {
        byte[] big = new byte[4_096];
        java.util.Arrays.fill(big, (byte) 'x');
        WIREMOCK.stubFor(get(anyUrl()).willReturn(aResponse()
                .withStatus(200).withBody(big)));
        ToolGateway gateway = gatewayFor(baseUrl(), 5_000, 1_024);
        ToolControlPlaneException e = catchThrowableOfType(() -> gateway.invoke(invocation()),
                ToolControlPlaneException.class);
        assertThat(e.reason()).isEqualTo(ToolControlReason.RESULT_OVERSIZE);
    }
}
