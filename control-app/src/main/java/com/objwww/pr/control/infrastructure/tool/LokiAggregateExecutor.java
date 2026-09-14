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
 * <p>severity 口径（A0 补充方案 §3）：可选参数，封闭集 ALL（缺省）/ERROR/WARN/INFO，
 * 未识别级别在发出前 INVALID_ARGS 拒绝（不自动判 INFO/健康）；过滤表达式由本执行器
 * 按已核对日志源 schema（detected_level 流标签）生成，<b>全量计数≠错误计数</b>——
 * 结果逐字段携带 severity/filter/window/coverage，ALL 聚合行在 Claim 准入面只作
 * CONTEXT（PrimaryClaimAdmission 确定性检查）。空结果语义按口径分岔：ALL 空窗=
 * NO_DATA（零数据如实呈现）；过滤级空=成功 count=0 且必须附覆盖警示（标签缺失/
 * 未采集/无流量都不能据此证明无故障）；源不可用仍是明确错误，不转成 0。
 *
 * <p>纪律同 {@link LogQueryExecutor}：service allowlist fail-closed 且越界<b>发出前</b>拒；
 * 窗幅 ≤900s；错误三态 EMPTY→NO_DATA / SOURCE_UNAVAILABLE / QUERY_FAILED；
 * 429 RATE_LIMITED；401/403 AUTH_FAILED；有界流读。
 */
public class LokiAggregateExecutor implements ToolExecutor {

    static final long MAX_WINDOW_SECONDS = 900;
    static final String DEFAULT_SERVICE = "control-app";
    /** severity 封闭集（缺省 ALL=通用计数；其余=明确选定等级，契约即口径） */
    static final Set<String> SEVERITIES = Set.of("ALL", "ERROR", "WARN", "INFO");
    /** severity 过滤面：已核对日志源 schema 的流标签（detected_level）；大小写不敏感精确级 */
    static final String SEVERITY_LABEL = "detected_level";
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
        String expr = "sum by (service_name) (count_over_time(" + selector(query)
                + "[" + windowSeconds + "s]))";
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
        return render(body, execution.resultLimitBytes(), query, expr);
    }

    /** 查询选择器：severity=ALL 只按 service；其余加 detected_level 精确级匹配 */
    static String selector(Query query) {
        String base = "{service_name=" + quote(query.service()) + "}";
        if ("ALL".equals(query.severity())) {
            return base;
        }
        return base.replace("}", ", " + SEVERITY_LABEL + "=~\"(?i)^"
                + query.severity().toLowerCase(java.util.Locale.ROOT) + "$\"}");
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
        // A0 补充方案 §3：severity 封闭集；未识别级别拒绝，不静默映射（未知≠INFO/健康）
        String severity = args.get("severity") == null ? "ALL"
                : String.valueOf(args.get("severity")).toUpperCase(java.util.Locale.ROOT);
        if (!SEVERITIES.contains(severity)) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: severity 只允许 " + SEVERITIES + "（缺省 ALL；"
                            + "ERROR/WARN/INFO 为明确选定等级计数，全量计数≠错误计数）");
        }
        return new Query(since, until, service, severity);
    }

    record Query(Instant since, Instant until, String service, String severity) {
    }

    private static Instant parseInstant(Object value, String field) {
        if (value == null) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: " + field + " 必填（ISO-8601 Instant 或 epoch 秒）");
        }
        String text = String.valueOf(value).trim();
        // epoch 秒直收（演示工程 2026-09-15：LLM 把 UTC startsAt 误当时区换算对象，
        // 两次真窗 -7h/-8h 偏移致日志窗全空——epoch 无歧义；ISO-8601 路径原样保留）
        if (text.matches("\\d{9,11}")) {
            return Instant.ofEpochSecond(Long.parseLong(text));
        }
        try {
            return Instant.parse(text);
        } catch (java.time.format.DateTimeParseException e) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: " + field + " 必须为 ISO-8601 Instant 或 epoch 秒");
        }
    }

    // ------------------------------------------------------------ 响应映射

    /**
     * Loki vector → 口径自明形状：{@code data.{window,severity,filter,coverage,
     * truncated,result:[{service,count}]}}。空结果分岔（A0 补充方案 §3/AS-02）：
     * ALL 空窗=NO_DATA；过滤级空=成功 count=0+覆盖警示（不证明无故障）。
     */
    byte[] render(byte[] lokiResponse, long resultLimitBytes, Query query,
            String filterExpr) throws IOException {
        JsonNode root;
        try (InputStream in = new java.io.ByteArrayInputStream(lokiResponse)) {
            root = JSON.readTree(in);
        }
        JsonNode vector = root.path("data").path("result");
        boolean filtered = !"ALL".equals(query.severity());
        boolean zeroByFilter = false;
        if (!vector.isArray() || vector.isEmpty()) {
            if (!filtered) {
                throw new ToolModelVisibleException(ToolModelVisibleReason.NO_DATA,
                        "NO_DATA: 窗内无日志聚合结果（空结果如实呈现，不伪造统计）");
            }
            // AS-01/AS-02：过滤级零计数是成功结果——count=0 必须同时注明覆盖情况
            zeroByFilter = true;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                (int) Math.min(resultLimitBytes + 1, 1 << 20));
        try (var gen = JSON.getFactory().createGenerator(out)) {
            gen.writeStartObject();
            gen.writeStringField("status", "success");
            gen.writeObjectFieldStart("data");
            gen.writeObjectFieldStart("window");
            gen.writeStringField("since", query.since().toString());
            gen.writeStringField("until", query.until().toString());
            gen.writeEndObject();
            gen.writeStringField("severity", query.severity());
            gen.writeStringField("filter", filterExpr);
            gen.writeObjectFieldStart("coverage");
            gen.writeStringField("severity_label", SEVERITY_LABEL);
            gen.writeStringField("collection_gaps", "unknown");
            gen.writeStringField("note", "本计数只反映查询窗内已采集且 " + SEVERITY_LABEL
                    + " 标签匹配的日志；标签缺失/未采集/无流量都计 0 或不计入，"
                    + "零计数不能据此证明无故障");
            gen.writeEndObject();
            gen.writeBooleanField("truncated", false);
            gen.writeArrayFieldStart("result");
            if (zeroByFilter) {
                gen.writeStartObject();
                gen.writeStringField("service", query.service());
                gen.writeNumberField("count", 0);
                gen.writeEndObject();
            }
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
