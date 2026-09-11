package com.objwww.pr.control.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolExecutor.ToolExecution;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EN-05 Prometheus 发现面执行器（§一 metrics P0 四工具：instant/catalog/label_values/
 * rules）——纯远程调用面，策略/账本归 Gateway-Agent（单一咽喉纪律）。
 *
 * <p>权限范围（T03）：selector/match 文本中声明 {@code service} / {@code service_name}
 * 正向匹配器时，其值必须落在 service allowlist——<b>发出前</b>拒绝（目标端点计数零，
 * 预算零扣：INVALID_ARGS 在 gateway invoke 前抛出）。未声明 service 匹配器的查询
 * （如按 job 的基础设施面）放行——范围纪律管"声明了服务却越权"，不砍合法非服务查询。
 *
 * <p>限长与错误两族同 {@link PrometheusQueryExecutor}：响应有界流读（limit+1 即断 →
 * RESULT_OVERSIZE）；429 → RATE_LIMITED；401/403 → AUTH_FAILED；5xx/网络 →
 * REMOTE_UNAVAILABLE；4xx 良构被拒 → QUERY_FAILED；success 零结果 → NO_DATA
 * （T02 校验/空结果如实呈现，不伪造序列）。
 *
 * <p>不整体实现 ToolExecutor（execute 契约）：四入口方法同形，注册面以方法引用
 * 各注册为独立工具执行器（单一执行器实例，策略/账本仍归 Gateway-Agent）。
 */
public class PrometheusApiExecutor {

    private static final long CLIENT_TIMEOUT_BUFFER_MILLIS = 2_000;
    private static final int COPY_BUFFER_SIZE = 8 * 1_024;
    private static final ObjectMapper JSON = new ObjectMapper();
    /** PromQL 标签匹配器提取：key 操作符 "value"（值内允许转义引号） */
    private static final Pattern LABEL_MATCHER =
            Pattern.compile("(\\w+)\\s*(!?=~?)\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    /** 范围纪律标签集（OTel Demo 双惯例；其余标签不属服务范围面） */
    private static final Set<String> SCOPE_LABELS = Set.of("service", "service_name");

    private final String baseUrl;
    private final Set<String> serviceAllowlist;
    private final HttpClient http;

    public PrometheusApiExecutor(String baseUrl, Set<String> serviceAllowlist) {
        this(baseUrl, serviceAllowlist, HttpClient.newHttpClient());
    }

    public PrometheusApiExecutor(String baseUrl, Set<String> serviceAllowlist, HttpClient http) {
        if (serviceAllowlist == null || serviceAllowlist.isEmpty()) {
            throw new IllegalArgumentException("prometheus service allowlist 不得为空（fail-closed）");
        }
        if (baseUrl == null || baseUrl.endsWith("/")) {
            throw new IllegalArgumentException("baseUrl 不得为空或以 / 结尾");
        }
        this.baseUrl = baseUrl;
        this.serviceAllowlist = Set.copyOf(serviceAllowlist);
        this.http = Objects.requireNonNull(http);
    }

    // ------------------------------------------------- 四工具入口（ToolExecutor 同形）

    /** prometheus.instant：expr,time → 向量（time 不得指向未来——冻结窗纪律的执行器面） */
    public byte[] instantQuery(ToolExecution execution) throws Exception {
        Map<String, Object> args = execution.validatedArgs();
        String query = textArg(args.get("query"), "query");
        long time = epochSecondsArg(args.get("time"));
        if (time > System.currentTimeMillis() / 1_000 + 60) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: time 不得指向未来（冻结窗纪律）");
        }
        checkServiceScope(query);
        byte[] body = getForBody("/api/v1/query?" + param("query", query)
                + "&" + param("time", String.valueOf(time)), execution);
        JsonNode result = parse(body).path("data").path("result");
        if (!result.isValueNode() && result.isEmpty()) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.NO_DATA,
                    "NO_DATA: 查询零结果（空结果如实呈现，不伪造序列）");
        }
        return body;
    }

    /** prometheus.catalog：match 过滤 → 指标名+type+unit（label 值 + metadata 两跳合并） */
    public byte[] catalogSearch(ToolExecution execution) throws Exception {
        Map<String, Object> args = execution.validatedArgs();
        Object match = args.get("match");
        String matchText = match == null ? null : String.valueOf(match);
        if (matchText != null) {
            checkServiceScope(matchText);
        }
        String namesPath = "/api/v1/label/__name__/values"
                + (matchText == null ? "" : "?" + param("match", matchText));
        JsonNode names = parse(getForBody(namesPath, execution));
        if (!names.has("data") || !names.path("data").isArray()
                || names.path("data").isEmpty()) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.NO_DATA,
                    "NO_DATA: 目录无匹配指标（空结果如实呈现，不伪造名称）");
        }
        JsonNode metadata = parse(getForBody("/api/v1/metadata", execution));
        Map<String, JsonNode> meta = new TreeMap<>();
        metadata.path("data").fields().forEachRemaining(e -> meta.put(e.getKey(), e.getValue()));

        ByteArrayOutputStream out = new ByteArrayOutputStream(
                (int) Math.min(execution.resultLimitBytes() + 1, 1 << 20));
        try (var gen = JSON.getFactory().createGenerator(out)) {
            gen.writeStartObject();
            gen.writeStringField("status", "success");
            gen.writeObjectFieldStart("data");
            gen.writeArrayFieldStart("result");
            List<String> sorted = new ArrayList<>();
            names.path("data").forEach(n -> sorted.add(n.asText()));
            java.util.Collections.sort(sorted);
            for (String name : sorted) {
                JsonNode m = meta.get(name);
                gen.writeStartObject();
                gen.writeStringField("name", name);
                gen.writeStringField("type", m == null ? "unknown" : m.path("type").asText("unknown"));
                gen.writeStringField("unit", m == null ? "" : m.path("unit").asText(""));
                gen.writeEndObject();
                gen.flush();
                if (out.size() > execution.resultLimitBytes()) {
                    throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE,
                            "RESULT_OVERSIZE: 响应超过 " + execution.resultLimitBytes()
                                    + " 字节上限（流式即断）");
                }
            }
            gen.writeEndArray();
            gen.writeEndObject();
            gen.writeEndObject();
        }
        return out.toByteArray();
    }

    /** prometheus.label_values：label[,match] → 值列表（先发现 label 再拼 PromQL） */
    public byte[] labelValues(ToolExecution execution) throws Exception {
        Map<String, Object> args = execution.validatedArgs();
        String label = textArg(args.get("label"), "label");
        Object match = args.get("match");
        String matchText = match == null ? null : String.valueOf(match);
        if (matchText != null) {
            checkServiceScope(matchText);
        }
        String path = "/api/v1/label/" + label + "/values"
                + (matchText == null ? "" : "?" + param("match", matchText));
        JsonNode body = parse(getForBody(path, execution));
        if (!body.path("data").isArray() || body.path("data").isEmpty()) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.NO_DATA,
                    "NO_DATA: 标签零取值（空结果如实呈现）");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                (int) Math.min(execution.resultLimitBytes() + 1, 1 << 20));
        try (var gen = JSON.getFactory().createGenerator(out)) {
            gen.writeStartObject();
            gen.writeStringField("status", "success");
            gen.writeObjectFieldStart("data");
            gen.writeArrayFieldStart("result");
            for (JsonNode value : body.path("data")) {
                gen.writeStartObject();
                gen.writeStringField("value", value.asText());
                gen.writeEndObject();
            }
            gen.writeEndArray();
            gen.writeEndObject();
            gen.writeEndObject();
        }
        return out.toByteArray();
    }

    /** prometheus.rules：alertname → 规则 expr/for/annotations（RCA 第一跳；未命中如实 NO_DATA） */
    public byte[] ruleLookup(ToolExecution execution) throws Exception {
        Map<String, Object> args = execution.validatedArgs();
        String alertname = textArg(args.get("alertname"), "alertname");
        JsonNode body = parse(getForBody("/api/v1/rules?type=alert", execution));
        JsonNode hit = null;
        String group = null;
        for (JsonNode g : body.path("data").path("groups")) {
            for (JsonNode rule : g.path("rules")) {
                if (alertname.equals(rule.path("name").asText())) {
                    hit = rule;
                    group = g.path("name").asText();
                    break;
                }
            }
            if (hit != null) {
                break;
            }
        }
        if (hit == null) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.NO_DATA,
                    "NO_DATA: 无该告警名的规则（不猜近似规则）");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                (int) Math.min(execution.resultLimitBytes() + 1, 1 << 20));
        try (var gen = JSON.getFactory().createGenerator(out)) {
            gen.writeStartObject();
            gen.writeStringField("status", "success");
            gen.writeObjectFieldStart("data");
            gen.writeArrayFieldStart("result");
            gen.writeStartObject();
            gen.writeStringField("group", group);
            gen.writeStringField("name", hit.path("name").asText());
            gen.writeStringField("query", hit.path("query").asText());
            gen.writeNumberField("duration", hit.path("duration").asLong(0));
            gen.writeObjectField("labels", hit.path("labels"));
            gen.writeObjectField("annotations", hit.path("annotations"));
            gen.writeEndObject();
            gen.writeEndArray();
            gen.writeEndObject();
            gen.writeEndObject();
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------ 范围纪律（T03 前置拒）

    /** selector 文本中声明的 service/service_name 正向匹配器必须落 allowlist（发出前） */
    void checkServiceScope(String selectorText) {
        Matcher matcher = LABEL_MATCHER.matcher(selectorText);
        while (matcher.find()) {
            if (!SCOPE_LABELS.contains(matcher.group(1)) || !"=".equals(matcher.group(2))) {
                continue;
            }
            String service = matcher.group(3);
            if (!serviceAllowlist.contains(service)) {
                throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                        "INVALID_ARGS: service 越出 allowlist: " + service);
            }
        }
    }

    // ---------------------------------------------------------------- 共用调用面

    /** GET + 有界流读 + 错误两族映射（PrometheusQueryExecutor 同律） */
    private byte[] getForBody(String pathAndQuery, ToolExecution execution) throws Exception {
        long remaining = Math.max(1,
                execution.deadlineEpochMillis() - System.currentTimeMillis());
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery))
                .timeout(Duration.ofMillis(remaining + CLIENT_TIMEOUT_BUFFER_MILLIS))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                    "工具调用被中断（临时远端故障）");
        } catch (IOException e) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                    "工具远端暂不可用（临时故障，可重试）");
        }
        int status = response.statusCode();
        try {
            if (status == 429) {
                throw new ToolModelVisibleException(ToolModelVisibleReason.RATE_LIMITED,
                        "指标源限流（可退避重试）");
            }
            if (status == 401 || status == 403) {
                throw new ToolControlPlaneException(ToolControlReason.AUTH_FAILED,
                        "AUTH_FAILED: 指标源凭证无效");
            }
            if (status >= 500) {
                throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                        "工具远端暂不可用（临时故障，可重试）");
            }
            if (status != 200) {
                throw new ToolControlPlaneException(ToolControlReason.QUERY_FAILED,
                        "QUERY_FAILED: 指标源拒绝良构查询（HTTP " + status + "）");
            }
            return readBounded(response.body(), execution.resultLimitBytes());
        } finally {
            response.body().close();
        }
    }

    private JsonNode parse(byte[] body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (!"success".equals(root.path("status").asText())) {
                throw new ToolControlPlaneException(ToolControlReason.QUERY_FAILED,
                        "QUERY_FAILED: 指标源返回 error 状态");
            }
            return root;
        } catch (IOException e) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                    "工具响应不可解析（临时故障，可重试）");
        }
    }

    /** 至多读 limit+1 字节；读到 limit+1 = RESULT_OVERSIZE（超大响应不进内存，T04） */
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
        }
    }

    private static String textArg(Object value, String field) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: " + field + " 必填");
        }
        return String.valueOf(value);
    }

    private static long epochSecondsArg(Object value) {
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: time 必须为整数 epoch 秒");
        }
    }

    private static String param(String name, String value) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
