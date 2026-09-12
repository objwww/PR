package com.objwww.pr.control.ops.application;

import com.objwww.pr.control.ops.domain.repository.MetricsRangeGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 监控页指标白名单代理服务（方案 §三.12「主机」区数据面 + 全量联通方案 §5.11/§6 B6）：
 * GET /api/metrics/query_range 的唯一查询集——query 参数只允许本类枚举的白名单键，
 * 键→预写 PromQL 模板在此固定，前端/调用方只可传时间窗与 step 数值，
 * 严禁透传任意 PromQL（防注入，方案 §9 防假绿第 3 条）。
 *
 * <p>诚实语义：
 * <ul>
 *   <li>白名单键存在但 Prometheus 无该指标（当前 deploy/alert/prometheus 只抓
 *       otel-collector 与 order-arena，无 node_exporter/alertmanager 抓取）→
 *       远端返回空 matrix，本服务如实返回空 series——前端显示「未采集/未知」，
 *       不以模拟数据填充；</li>
 *   <li>Prometheus 不可达/非 200/应答非法 → {@link MetricsSourceUnavailableException}
 *       → HTTP 503，前端显示「监控数据源未配置/不可达」；</li>
 *   <li>应答必带 asOf（服务层钉 now），新鲜度判断归前端。</li>
 * </ul>
 *
 * <p>参数边界（自由数值，非法 → IllegalArgumentException → HTTP 400）：
 * start/end 整数 epoch 秒且 end &gt; start；窗幅 ≤ {@value #MAX_WINDOW_SECONDS}s；
 * step ∈ [{@value #MIN_STEP_SECONDS}, {@value #MAX_STEP_SECONDS}]s；
 * 估算点数 (end-start)/step ≤ {@value #MAX_POINTS}（超 → 提示增大 step）。
 */
public class MetricsWhitelistService {

    /** 窗幅上限 24h（监控页小趋势图最久窗口） */
    static final long MAX_WINDOW_SECONDS = 86_400;
    /** step 下限 15s（与 deploy/alert/prometheus scrape_interval 对齐） */
    static final long MIN_STEP_SECONDS = 15;
    /** step 上限 1h */
    static final long MAX_STEP_SECONDS = 3_600;
    /** 单序列估算点数上限（防一次拉爆代理与前端） */
    static final long MAX_POINTS = 1_000;

    /**
     * 白名单键集（B6 预写 PromQL 模板；单位随键固定）。
     * 模板对齐标准 exporter 口径：node_exporter（主机三维）、alertmanager
     * （接收速率）、control-app 自暴露计数器 AlertMetrics.rca_attempt_finished_total
     * （run 吞吐，attempt 级近似）。三族指标当前均未被 AM0 Prometheus 抓取
     * （deploy/alert/prometheus 只抓 otel-collector/order-arena）→ 远端恒空 →
     * 前端「未采集」；接入抓取后零改动自动有数。
     */
    public enum WhitelistKey {
        HOST_CPU_USAGE("host_cpu_usage", "%",
                "100 - (avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[5m])) * 100)"),
        HOST_MEM_USAGE("host_mem_usage", "%",
                "(1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)) * 100"),
        HOST_DISK_USAGE("host_disk_usage", "%",
                "100 * (1 - (sum by (instance) (node_filesystem_avail_bytes{fstype!~\"tmpfs|overlay\"})"
                        + " / sum by (instance) (node_filesystem_size_bytes{fstype!~\"tmpfs|overlay\"})))"),
        ALERT_RECEIVE_RATE("alert_receive_rate", "条/秒",
                "sum(rate(alertmanager_alerts_received_total[5m]))"),
        // 真实计数器（AlertMetrics.attemptFinished）：attempt 级完成吞吐近似 run 级
        // （无 run 级计数器——诚实近似，键名保留 B6 契约）；当前 AM0 Prometheus
        // 未抓 otelcol-control:9465 → 恒空 → 前端「未采集」，接入后自动有数
        RUN_THROUGHPUT("run_throughput", "次/秒",
                "sum(rate(rca_attempt_finished_total[5m]))");

        private final String key;
        private final String unit;
        private final String promql;

        WhitelistKey(String key, String unit, String promql) {
            this.key = key;
            this.unit = unit;
            this.promql = promql;
        }

        public String key() {
            return key;
        }

        public String unit() {
            return unit;
        }

        public String promql() {
            return promql;
        }

        static WhitelistKey fromKey(String key) {
            for (WhitelistKey k : values()) {
                if (k.key.equals(key)) {
                    return k;
                }
            }
            throw new IllegalArgumentException("query 不在白名单内（允许：" + allowedKeys() + "）");
        }

        static String allowedKeys() {
            StringBuilder sb = new StringBuilder();
            for (WhitelistKey k : values()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(k.key);
            }
            return sb.toString();
        }
    }

    /** 单数据点（epoch 秒 + 值；NaN/Inf 点在解析期剔除——Prometheus 会如实回 "NaN"） */
    public record MetricPoint(long epochSec, double value) {
    }

    /** 单序列（name 由 labels 推导：优先 instance，其次全标签，兜底 value） */
    public record MetricSeries(String name, List<MetricPoint> points) {
    }

    /** 区间应答（record 字段名即 JSON 契约；series 空 = 已查询但未采集） */
    public record MetricsRangeResponse(String query, String unit, List<MetricSeries> series,
                                       Instant asOf) {
    }

    private final MetricsRangeGateway gateway;
    private final Supplier<Instant> now;
    private final ObjectMapper json;

    public MetricsWhitelistService(MetricsRangeGateway gateway, Supplier<Instant> now) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.now = Objects.requireNonNull(now, "now");
        this.json = new ObjectMapper();
    }

    public MetricsRangeResponse queryRange(String query, String start, String end, String step) {
        WhitelistKey key = WhitelistKey.fromKey(query);
        long startSec = parseEpochSeconds(start, "start");
        long endSec = parseEpochSeconds(end, "end");
        long stepSec = parseStepSeconds(step);
        if (endSec <= startSec) {
            throw new IllegalArgumentException("end 必须大于 start");
        }
        if (endSec - startSec > MAX_WINDOW_SECONDS) {
            throw new IllegalArgumentException("查询窗幅超限（end-start ≤ " + MAX_WINDOW_SECONDS + "s）");
        }
        if ((endSec - startSec) / stepSec + 1 > MAX_POINTS) {
            throw new IllegalArgumentException("估算点数超限（≤ " + MAX_POINTS + "），请增大 step");
        }

        String body = gateway.queryRange(key.promql(), startSec, endSec, stepSec);
        return new MetricsRangeResponse(key.key(), key.unit(), parseMatrix(body), now.get());
    }

    /** Prometheus matrix JSON → 序列清单；应答非法（非 success/缺 data）按源不可用论（503 族） */
    private List<MetricSeries> parseMatrix(String body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (Exception e) {
            throw new MetricsSourceUnavailableException("指标源应答非法（非 JSON）", e);
        }
        if (!"success".equals(root.path("status").asText(null))) {
            throw new MetricsSourceUnavailableException("指标源应答失败（status="
                    + root.path("status").asText("缺失") + "）");
        }
        JsonNode result = root.path("data").path("result");
        if (!result.isArray()) {
            throw new MetricsSourceUnavailableException("指标源应答非法（缺 data.result）");
        }
        List<MetricSeries> series = new ArrayList<>();
        for (JsonNode entry : result) {
            List<MetricPoint> points = new ArrayList<>();
            for (JsonNode value : entry.path("values")) {
                if (!value.isArray() || value.size() < 2) {
                    continue;
                }
                double v;
                try {
                    v = Double.parseDouble(value.get(1).asText());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (!Double.isFinite(v)) {
                    continue;
                }
                points.add(new MetricPoint(value.get(0).asLong(), v));
            }
            series.add(new MetricSeries(seriesName(entry.path("metric")), points));
        }
        return series;
    }

    /** 序列名：instance 标签优先；无则全标签 k=v 串；零标签兜底 value */
    private static String seriesName(JsonNode metric) {
        if (!metric.isObject() || metric.isEmpty()) {
            return "value";
        }
        JsonNode instance = metric.get("instance");
        if (instance != null && instance.isTextual()) {
            return instance.asText();
        }
        StringBuilder sb = new StringBuilder();
        metric.properties().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(e.getKey()).append("=").append(e.getValue().asText());
                });
        return sb.toString();
    }

    private static long parseEpochSeconds(String value, String field) {
        try {
            return Long.parseLong(Objects.requireNonNull(value, field));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " 必须为整数 epoch 秒");
        }
    }

    private static long parseStepSeconds(String value) {
        long step;
        try {
            step = Long.parseLong(Objects.requireNonNull(value, "step"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("step 必须为整数秒");
        }
        if (step < MIN_STEP_SECONDS || step > MAX_STEP_SECONDS) {
            throw new IllegalArgumentException("step 超限（" + MIN_STEP_SECONDS + "s ~ "
                    + MAX_STEP_SECONDS + "s）");
        }
        return step;
    }
}
