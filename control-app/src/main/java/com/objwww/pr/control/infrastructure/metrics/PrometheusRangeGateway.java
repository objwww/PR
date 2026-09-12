package com.objwww.pr.control.infrastructure.metrics;

import com.objwww.pr.control.ops.application.MetricsSourceUnavailableException;
import com.objwww.pr.control.ops.domain.repository.MetricsRangeGateway;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * {@link MetricsRangeGateway} 的 Prometheus HTTP 实现（监控页白名单代理远端面，
 * 方案 §三.12 + 全量联通方案 §5.11/§6 B6）。
 *
 * <p>刻意不复用 {@code infrastructure/tool/PrometheusQueryExecutor}：那是 Agent R0
 * 工具面（ToolExecutor 身份、deadline/账本/控制面异常族），监控页是 ops 只读投影，
 * 借工具身份会混淆审计与限流语义——两面无共享代码，仅同走
 * {@code app.alert.am4.prometheus.base-url} 配置源（单一地址事实源）。
 *
 * <p>有界读取：响应体至多读 {@value #MAX_BODY_BYTES}+1 字节，超限即断按源不可用论；
 * 非 200 / IO / 中断 / 超时 → {@link MetricsSourceUnavailableException}（HTTP 面 503），
 * 底层细节（状态码/异常文本）不透传给调用方以外的面——固定文案，脱敏同工具面纪律。
 */
public class PrometheusRangeGateway implements MetricsRangeGateway {

    /** 响应体上限 1 MiB（白名单模板 + 点数上限约束下正常响应 ≪ 此值） */
    static final long MAX_BODY_BYTES = 1 << 20;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int COPY_BUFFER_SIZE = 8 * 1_024;

    private final String baseUrl;
    private final HttpClient http;

    public PrometheusRangeGateway(String baseUrl) {
        this(baseUrl, HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    public PrometheusRangeGateway(String baseUrl, HttpClient http) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        if (baseUrl.endsWith("/")) {
            throw new IllegalArgumentException("baseUrl 不得以 / 结尾");
        }
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public String queryRange(String promql, long startEpochSec, long endEpochSec, long stepSec) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/query_range?"
                        + param("query", promql) + "&"
                        + param("start", startEpochSec) + "&"
                        + param("end", endEpochSec) + "&"
                        + param("step", stepSec)))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MetricsSourceUnavailableException("指标源请求被中断", e);
        } catch (IOException e) {
            throw new MetricsSourceUnavailableException("指标源不可达", e);
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new MetricsSourceUnavailableException(
                        "指标源应答非 200（status=" + response.statusCode() + "）");
            }
            return new String(readBounded(body, MAX_BODY_BYTES), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MetricsSourceUnavailableException("指标源应答读取失败", e);
        }
    }

    /** 至多读 limit+1 字节，超限即断（PrometheusQueryExecutor F17 同律） */
    private static byte[] readBounded(InputStream body, long limitBytes) throws IOException {
        long cap = limitBytes + 1;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(
                (int) Math.min(cap, 1 << 20));
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = body.read(buffer)) != -1) {
            if (read > cap - total) {
                throw new MetricsSourceUnavailableException(
                        "指标源应答超过 " + limitBytes + " 字节上限（有界流读即断）");
            }
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toByteArray();
    }

    private static String param(String name, Object value) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8);
    }
}
