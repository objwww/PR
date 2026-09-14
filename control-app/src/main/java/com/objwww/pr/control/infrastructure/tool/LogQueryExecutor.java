package com.objwww.pr.control.infrastructure.tool;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
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
 * Loki query_range 日志工具执行器（EX-B2：logs 真实源，盘点门签字后落码）：
 * GET {base}/loki/api/v1/query_range?query={service_name="<service>"}&start&end(ns)
 * &limit=201——响应<b>流式</b>转统一响应形状
 * {@code {"status":"success","data":{"result":[{ts,service,line}…],"truncated":bool}}}。
 * 原始体大小与结果预算解耦（2026-09-12 run24 教训：错误突发窗 200 行×长栈行原文
 * 恒超 resultLimit，整包有界读必抛终止族 RESULT_OVERSIZE 炸死主任务）——行渲染到
 * 预算即止 + truncated=true，有界诚实截断；单行可越限一个行幅（行长保真不裁字）。
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
        // 200：流式渲染——原始体与结果预算解耦（run24：整包有界读在突发窗恒爆
        // resultLimit，终止族炸死主任务；行级截断才是"先聚合后读行"的本义）
        try (InputStream body = response.body()) {
            return render(body, query.service, execution.resultLimitBytes());
        }
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

    /** ISO-8601/epoch 秒良构面归 executor 域内判（B-32：裸 DateTimeParseException 不外漏） */
    private static Instant parseInstant(Object value, String field) {
        String text = String.valueOf(value).trim();
        // epoch 秒直收（与 LokiAggregateExecutor 同因：LLM 时区换算不可靠，epoch 无歧义）
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
     * Loki 响应 → 统一形状（纯流式）：Jackson 流式解析 data.result[].values[]，
     * 行数 201 = 截断标记交 200 行；行渲染字节到预算即止 + truncated=true；
     * 0 行 = 模型可见 EMPTY(NO_DATA)；原始体消费超 {@value #RAW_READ_CAP_FACTOR}×
     * limit（无行可渲染的巨型垃圾流防御）= 止读，已渲染行以 truncated 交付。
     * 半包/不合约（未触防御上限）= SOURCE_UNAVAILABLE 不投递半成品。
     */
    byte[] render(InputStream lokiBody, String service, long resultLimitBytes)
            throws IOException {
        CountingInputStream in = new CountingInputStream(lokiBody,
                Math.max(resultLimitBytes, 1) * RAW_READ_CAP_FACTOR);
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                (int) Math.min(resultLimitBytes + 1_024, 1 << 20));
        int kept = 0;
        boolean truncated = false;
        try (JsonGenerator gen = JSON.getFactory().createGenerator(out);
             JsonParser parser = JSON.getFactory().createParser(in)) {
            gen.writeStartObject();
            gen.writeStringField("status", "success");
            gen.writeObjectFieldStart("data");
            gen.writeArrayFieldStart("result");
            try {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    throw new ToolModelVisibleException(
                            ToolModelVisibleReason.SOURCE_UNAVAILABLE,
                            "日志源响应不合约（半包不投递，可重试）");
                }
                parsing:
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String topName = parser.currentName();
                    JsonToken topValue = parser.nextToken();
                    if (!"data".equals(topName) || topValue != JsonToken.START_OBJECT) {
                        parser.skipChildren();
                        continue;
                    }
                    while (parser.nextToken() == JsonToken.FIELD_NAME) {
                        String dataName = parser.currentName();
                        JsonToken dataValue = parser.nextToken();
                        if (!"result".equals(dataName) || dataValue != JsonToken.START_ARRAY) {
                            parser.skipChildren();
                            continue;
                        }
                        while (parser.nextToken() == JsonToken.START_OBJECT) {
                            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                                String streamName = parser.currentName();
                                JsonToken streamValue = parser.nextToken();
                                if (!"values".equals(streamName)
                                        || streamValue != JsonToken.START_ARRAY) {
                                    parser.skipChildren();
                                    continue;
                                }
                                while (parser.nextToken() == JsonToken.START_ARRAY) {
                                    parser.nextToken();          // nsEpoch
                                    String ns = parser.getText();
                                    parser.nextToken();          // line
                                    String line = parser.getText();
                                    parser.nextToken();          // END_ARRAY
                                    if (kept >= ROW_LIMIT) {
                                        truncated = true;
                                        break parsing;
                                    }
                                    Instant ts = Instant.ofEpochMilli(
                                            Long.parseLong(ns) / 1_000_000L);
                                    gen.writeStartObject();
                                    gen.writeStringField("ts", ts.toString());
                                    gen.writeStringField("service", service);
                                    gen.writeStringField("line", line);
                                    gen.writeEndObject();
                                    kept++;
                                    gen.flush();
                                    if (out.size() > resultLimitBytes || in.hitCap()) {
                                        truncated = true;
                                        break parsing;
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (JsonProcessingException | NumberFormatException e) {
                if (in.hitCap() && kept > 0) {
                    // 防御上限止读：行样本身份诚实，交 truncated 语义
                    truncated = true;
                } else {
                    throw new ToolModelVisibleException(
                            ToolModelVisibleReason.SOURCE_UNAVAILABLE,
                            "日志源响应不合约（半包不投递，可重试）");
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

    /** 原始体消费上限系数（防御面：无 values 的巨型垃圾流不进整包内存） */
    private static final long RAW_READ_CAP_FACTOR = 16;

    /** 计数止读流：消费过 cap 后返回 EOF（行间检查 + 硬停读双保险） */
    private static final class CountingInputStream extends InputStream {
        private final InputStream delegate;
        private final long cap;
        private long count;
        private boolean hitCap;

        CountingInputStream(InputStream delegate, long cap) {
            this.delegate = delegate;
            this.cap = cap;
        }

        @Override
        public int read() throws IOException {
            if (hitCap) {
                return -1;
            }
            int r = delegate.read();
            if (r >= 0) {
                tally(1);
            }
            return r;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (hitCap) {
                return -1;
            }
            int r = delegate.read(b, off, len);
            if (r > 0) {
                tally(r);
            }
            return r;
        }

        private void tally(int n) {
            count += n;
            if (count > cap) {
                hitCap = true;
            }
        }

        boolean hitCap() {
            return hitCap;
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
