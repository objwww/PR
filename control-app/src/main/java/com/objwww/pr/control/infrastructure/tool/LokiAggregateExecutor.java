package com.objwww.pr.control.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolExecutor;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * EN-05 log_error_aggregate 执行器（§一 logs P0，SigNoz 两级设计第一步：
 * <b>先聚合后读行</b>）——Loki instant metric 查询
 * {@code sum by (service_name) (count_over_time({service_name="svc"}[window]))}，
 * 确定性聚合零 LLM；逐行读数由 logs.query 承担（超大响应结构性不可达）。
 *
 * <p>纪律同 {@link LogQueryExecutor}：service allowlist fail-closed 且越界<b>发出前</b>拒；
 * 窗幅 ≤900s；错误三态 EMPTY→NO_DATA / SOURCE_UNAVAILABLE / QUERY_FAILED；
 * 429 RATE_LIMITED；401/403 AUTH_FAILED；有界流读。
 */
public class LokiAggregateExecutor implements ToolExecutor {

    static final long MAX_WINDOW_SECONDS = 900;
    static final String DEFAULT_SERVICE = "control-app";
    private static final long CLIENT_TIMEOUT_BUFFER_MILLIS = 2_000;
    private static final int COPY_BUFFER_SIZE = 8 * 1_024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final Set<String> serviceAllowlist;
    private final HttpClient http;

    public LokiAggregateExecutor(String lokiBaseUrl, Set<String> serviceAllowlist) {
        this(lokiBaseUrl, serviceAllowlist, HttpClient.newHttpClient());
    }

    public LokiAggregateExecutor(String lokiBaseUrl, Set<String> serviceAllowlist,
            HttpClient http) {
        if (serviceAllowlist == null || serviceAllowlist.isEmpty()) {
            throw new IllegalArgumentException("logs service allowlist 不得为空（fail-closed）");
        }
        if (lokiBaseUrl == null || lokiBaseUrl.endsWith("/")) {
            throw new IllegalArgumentException("lokiBaseUrl 不得为空或以 / 结尾");
        }
        this.baseUrl = lokiBaseUrl;
        this.serviceAllowlist = Set.copyOf(serviceAllowlist);
        this.http = Objects.requireNonNull(http);
    }

    @Override
    public byte[] execute(ToolExecution execution) throws Exception {
        Query query = parseArgs(execution.validatedArgs(), serviceAllowlist);
        long windowSeconds = Math.max(1,
                (query.until().toEpochMilli() - query.since().toEpochMilli()) / 1_000);
        String expr = "sum by (service_name) (count_over_time({service_name="
                + quote(query.service()) + "}[" + windowSeconds + "s]))";
        long remaining = Math.max(1,
                execution.deadlineEpochMillis() - System.currentTimeMillis());
        URI uri = URI.create(baseUrl + "/loki/api/v1/query?"
                + param("query", expr)
                + "&time=" + query.until().toEpochMilli() * 1_000_000L);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(remaining + CLIENT_TIMEOUT_BUFFER_MILLIS))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolModelVisibleException(ToolModelVisibleReason.SOURCE_UNAVAILABLE,
                    "日志源调用被中断（临时源故障）");
        } catch (IOException e) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.SOURCE_UNAVAILABLE,
                    "日志源暂不可用（临时故障，可重试）");
        }
        int status = response.statusCode();
        byte[] body;
        try {
            if (status == 429) {
                throw new ToolModelVisibleException(ToolModelVisibleReason.RATE_LIMITED,
                        "日志源限流（可退避重试）");
            }
            if (status == 401 || status == 403) {
                throw new ToolControlPlaneException(ToolControlReason.AUTH_FAILED,
                        "AUTH_FAILED: 日志源凭证无效");
            }
            if (status >= 500) {
                throw new ToolModelVisibleException(ToolModelVisibleReason.SOURCE_UNAVAILABLE,
                        "日志源暂不可用（临时故障，可重试）");
            }
            if (status != 200) {
                throw new ToolControlPlaneException(ToolControlReason.QUERY_FAILED,
                        "QUERY_FAILED: 日志源拒绝良构查询（HTTP " + status + "）");
            }
            body = readBounded(response.body(), execution.resultLimitBytes());
        } finally {
            response.body().close();
        }
        return render(body, execution.resultLimitBytes());
    }

    // ------------------------------------------------------------ 参数语义（静态 = L0 可测）

    Query parseArgs(Map<String, Object> args, Set<String> allowlist) {
        Instant since = parseInstant(args.get("since"), "since");
        Instant until = parseInstant(args.get("until"), "until");
        long windowMillis = until.toEpochMilli() - since.toEpochMilli();
        if (windowMillis < 0 || windowMillis > MAX_WINDOW_SECONDS * 1_000) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: 查询窗幅超限（0 ≤ until-since ≤ "
                            + MAX_WINDOW_SECONDS + "s）");
        }
        String service = args.get("service") == null
                ? DEFAULT_SERVICE : String.valueOf(args.get("service"));
        if (!allowlist.contains(service)) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: service 越出 allowlist");
        }
        return new Query(since, until, service);
    }

    record Query(Instant since, Instant until, String service) {
    }

    private static Instant parseInstant(Object value, String field) {
        if (value == null) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: " + field + " 必填（ISO-8601 Instant）");
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (java.time.format.DateTimeParseException e) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: " + field + " 必须为 ISO-8601 Instant");
        }
    }

    // ------------------------------------------------------------ 响应映射

    /** Loki vector → 统一形状 {@code data.result=[{service,count}]}；空 = NO_DATA */
    byte[] render(byte[] lokiResponse, long resultLimitBytes) throws IOException {
        JsonNode root;
        try (InputStream in = new java.io.ByteArrayInputStream(lokiResponse)) {
            root = JSON.readTree(in);
        }
        JsonNode vector = root.path("data").path("result");
        if (!vector.isArray() || vector.isEmpty()) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.NO_DATA,
                    "NO_DATA: 窗内无日志聚合结果（空结果如实呈现，不伪造统计）");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                (int) Math.min(resultLimitBytes + 1, 1 << 20));
        try (var gen = JSON.getFactory().createGenerator(out)) {
            gen.writeStartObject();
            gen.writeStringField("status", "success");
            gen.writeObjectFieldStart("data");
            gen.writeBooleanField("truncated", false);
            gen.writeArrayFieldStart("result");
            for (JsonNode entry : vector) {
                gen.writeStartObject();
                gen.writeStringField("service",
                        entry.path("metric").path("service_name").asText(""));
                gen.writeNumberField("count",
                        entry.path("value").path(1).asLong(0));
                gen.writeEndObject();
                gen.flush();
                if (out.size() > resultLimitBytes) {
                    throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE,
                            "RESULT_OVERSIZE: 响应超过 " + resultLimitBytes + " 字节上限");
                }
            }
            gen.writeEndArray();
            gen.writeEndObject();
            gen.writeEndObject();
        }
        return out.toByteArray();
    }

    /** 有界读：至多 limit+1 字节，读到即断（半包即弃，同 LogQueryExecutor） */
    private static byte[] readBounded(InputStream body, long limitBytes) throws IOException {
        try (body) {
            long cap = Math.max(1, limitBytes) + 1;
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    (int) Math.min(cap, 1 << 20));
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            long total = 0;
            int read;
            while ((read = body.read(buffer)) != -1) {
                long allowed = cap - total;
                if (read > allowed) {
                    throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE,
                            "RESULT_OVERSIZE: 响应超过 " + limitBytes + " 字节上限（有界流读即断）");
                }
                out.write(buffer, 0, read);
                total += read;
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.SOURCE_UNAVAILABLE,
                    "日志源响应中断（半包不投递，可重试）");
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String param(String name, String value) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
