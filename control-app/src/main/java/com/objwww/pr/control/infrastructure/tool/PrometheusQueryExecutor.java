package com.objwww.pr.control.infrastructure.tool;

import com.objwww.pr.control.alert.application.tool.ToolExecutor;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Prometheus query_range 工具执行器（AM4 M4-27，首个真实工具）：
 * 纯远程调用面——GET {base}/api/v1/query_range?query&start&end&step，原样返回响应字节。
 * 策略/校验/账本全部归 Gateway 与 Agent（单一咽喉纪律）；HTTP 429 → RATE_LIMITED，
 * 其余非 200 / 网络异常 → REMOTE_UNAVAILABLE（脱敏固定文案，底层细节不透传）。
 *
 * <p>客户端请求超时 = deadline 剩余 + 缓冲（与 ToolGateway 硬 deadline 双定时器同窗会
 * 竞争，缓冲让 Gateway 确定性先到先取消——itW02 同纪律）。
 */
public class PrometheusQueryExecutor implements ToolExecutor {

    private static final long CLIENT_TIMEOUT_BUFFER_MILLIS = 2_000;

    private final String baseUrl;
    private final HttpClient http;

    public PrometheusQueryExecutor(String baseUrl) {
        this(baseUrl, HttpClient.newHttpClient());
    }

    public PrometheusQueryExecutor(String baseUrl, HttpClient http) {
        this.baseUrl = Objects.requireNonNull(baseUrl);
        if (baseUrl.endsWith("/")) {
            throw new IllegalArgumentException("baseUrl 不得以 / 结尾");
        }
        this.http = Objects.requireNonNull(http);
    }

    @Override
    public byte[] execute(ToolExecution execution) throws Exception {
        Map<String, Object> args = execution.validatedArgs();
        long remaining = Math.max(1,
                execution.deadlineEpochMillis() - System.currentTimeMillis());
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/query_range?"
                + param("query", args.get("query")) + "&"
                + param("start", args.get("start")) + "&"
                + param("end", args.get("end")) + "&"
                + param("step", args.get("step"))))
                .timeout(Duration.ofMillis(remaining + CLIENT_TIMEOUT_BUFFER_MILLIS))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                    "工具调用被中断（临时远端故障）");
        } catch (IOException e) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                    "工具远端暂不可用（临时故障，可重试）");
        }
        if (response.statusCode() == 429) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.RATE_LIMITED,
                    "指标源限流（可退避重试）");
        }
        if (response.statusCode() != 200) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                    "工具远端暂不可用（临时故障，可重试）");
        }
        return response.body();
    }

    private static String param(String name, Object value) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8);
    }
}
