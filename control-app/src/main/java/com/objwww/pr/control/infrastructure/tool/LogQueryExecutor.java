package com.objwww.pr.control.infrastructure.tool;

import com.fasterxml.jackson.core.JsonGenerator;
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
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Loki query_range 日志工具执行器（EX-B2：logs 真实源，盘点门签字后落码）：
 * GET {base}/loki/api/v1/query_range?query={service_name="<service>"}&start&end(ns)
 * &limit=201——响应有界读（resultLimit+1 超大即断）后流式转统一响应形状
 * {@code {"status":"success","data":{"result":[{ts,service,line}…],"truncated":bool}}}。
 *
 * <p>无数据三态（卡面硬要求，契约 §2）：EMPTY → 模型可见 NO_DATA；
 * SOURCE_UNAVAILABLE → 连接/超时/半包/5xx（模型可见可重试，与 PROMETHEUS 面
 * REMOTE_UNAVAILABLE 分因便于台账判源）；QUERY_FAILED → Loki 对良构查询回 4xx
 * （控制面终止族）。401/403 → AUTH_FAILED；429 → RATE_LIMITED。
 *
 * <p>语义约束（executor 域内判，同 ChangeQueryExecutor 纪律）：窗幅 ≤
 * {@value #MAX_WINDOW_SECONDS}s；service 缺省 {@value #DEFAULT_SERVICE} 且必须在
 * allowlist（构造器 fail-closed 先于任何依赖检查——B-24 次序律）。
 */
public class LogQueryExecutor implements ToolExecutor {

    static final long MAX_WINDOW_SECONDS = 900;
    static final int ROW_LIMIT = 200;
    static final String DEFAULT_SERVICE = "control-app";
    private static final long CLIENT_TIMEOUT_BUFFER_MILLIS = 2_000;
    private static final int COPY_BUFFER_SIZE = 8 * 1_024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final Set<String> serviceAllowlist;
    private final HttpClient http;

    public LogQueryExecutor(String lokiBaseUrl, Set<String> serviceAllowlist) {
        this(lokiBaseUrl, serviceAllowlist, HttpClient.newHttpClient());
    }

    public LogQueryExecutor(String lokiBaseUrl, Set<String> serviceAllowlist, HttpClient http) {
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
        Query query = parseArgs(execution.validatedArgs());
        long remaining = Math.max(1,
                execution.deadlineEpochMillis() - System.currentTimeMillis());
        String selector = "{service_name=" + quote(query.service) + "}";
        URI uri = URI.create(baseUrl + "/loki/api/v1/query_range?"
                + param("query", selector)
                + "&start=" + query.since.toEpochMilli() * 1_000_000L
                + "&end=" + query.until.toEpochMilli() * 1_000_000L
                + "&limit=" + (ROW_LIMIT + 1)
                + "&direction=backward");
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
        if (status == 429) {
            response.body().close();
            throw new ToolModelVisibleException(ToolModelVisibleReason.RATE_LIMITED,
                    "日志源限流（可退避重试）");
        }
        if (status == 401 || status == 403) {
            response.body().close();
            throw new ToolControlPlaneException(ToolControlReason.AUTH_FAILED,
                    "AUTH_FAILED: 日志源凭证无效");
        }
        if (status >= 500) {
            response.body().close();
            throw new ToolModelVisibleException(ToolModelVisibleReason.SOURCE_UNAVAILABLE,
                    "日志源暂不可用（临时故障，可重试）");
        }
        if (status != 200) {
            response.body().close();
            throw new ToolControlPlaneException(ToolControlReason.QUERY_FAILED,
                    "QUERY_FAILED: 日志源拒绝良构查询（HTTP " + status + "）");
        }
        byte[] body = readBounded(response.body(), execution.resultLimitBytes());
        return render(body, query.service, execution.resultLimitBytes());
    }

    // ------------------------------------------------------------ 参数语义

    Query parseArgs(Map<String, Object> args) {
        Instant since = parseInstant(args.get("since"), "since");
        Instant until = parseInstant(args.get("until"), "until");
        if (until.isBefore(since) || until.toEpochMilli() - since.toEpochMilli()
                > MAX_WINDOW_SECONDS * 1_000) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: 查询窗幅超限（0 ≤ until-since ≤ " + MAX_WINDOW_SECONDS + "s）");
        }
        String service = args.get("service") == null
                ? DEFAULT_SERVICE : String.valueOf(args.get("service"));
        if (!serviceAllowlist.contains(service)) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: service 越出 allowlist");
        }
        return new Query(since, until, service);
    }

    record Query(Instant since, Instant until, String service) {
    }

    /** ISO-8601 良构面归 executor 域内判（B-32：裸 DateTimeParseException 不外漏） */
    private static Instant parseInstant(Object value, String field) {
        try {
            return Instant.parse(String.valueOf(value));
        } catch (java.time.format.DateTimeParseException e) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: " + field + " 必须为 ISO-8601 Instant");
        }
    }

    // ------------------------------------------------------------ 响应映射

    /**
     * Loki 响应 → 统一形状：遍历 streams.values（[nsEpoch, line, …]），行数 201 =
     * 截断标记交 200 行；0 行 = 模型可见 EMPTY(NO_DATA)；行渲染字节流式超限即断。
     */
    byte[] render(byte[] lokiResponse, String service, long resultLimitBytes)
            throws IOException {
        JsonNode root;
        try (InputStream in = new java.io.ByteArrayInputStream(lokiResponse)) {
            root = JSON.readTree(in);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                (int) Math.min(resultLimitBytes + 1, 1 << 20));
        int kept = 0;
        boolean truncated = false;
        try (JsonGenerator gen = JSON.getFactory().createGenerator(out)) {
            gen.writeStartObject();
            gen.writeStringField("status", "success");
            gen.writeObjectFieldStart("data");
            gen.writeArrayFieldStart("result");
            Iterator<JsonNode> streams = root.path("data").path("result").elements();
            while (streams.hasNext() && !truncated) {
                Iterator<JsonNode> values = streams.next().path("values").elements();
                while (values.hasNext()) {
                    JsonNode entry = values.next();
                    if (kept >= ROW_LIMIT) {
                        truncated = true;
                        break;
                    }
                    Instant ts = Instant.ofEpochMilli(
                            Long.parseLong(entry.get(0).asText()) / 1_000_000L);
                    gen.writeStartObject();
                    gen.writeStringField("ts", ts.toString());
                    gen.writeStringField("service", service);
                    gen.writeStringField("line", entry.get(1).asText());
                    gen.writeEndObject();
                    kept++;
                    gen.flush();
                    if (out.size() > resultLimitBytes) {
                        throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE,
                                "RESULT_OVERSIZE: 响应超过 " + resultLimitBytes + " 字节上限");
                    }
                }
            }
            gen.writeEndArray();
            gen.writeBooleanField("truncated", truncated);
            gen.writeEndObject();
            gen.writeEndObject();
        }
        if (kept == 0) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.NO_DATA,
                    "EMPTY: 窗内无日志（正常空结果，非故障）");
        }
        return out.toByteArray();
    }

    /** 有界读：至多 limit+1 字节，读到即断（超大响应不进内存；流断 = 半包即弃） */
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
