package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolRisk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EN-05 真实工具目录（§一 P0 具名清单的注册面）：§一 snake 名 ↔ 本库 dotted 工具 id
 * 的映射固定于此（promql_instant_query=prometheus.instant、metric_catalog_search=
 * prometheus.catalog、label_values_query=prometheus.label_values、alert_rule_lookup=
 * prometheus.rules、log_error_aggregate=logs.aggregate、change_event_diff=change.diff、
 * docker_ps/docker_inspect=docker.ps/docker.inspect、alert_history_query=alert.history；
 * loki_logql_query=既有 logs.query 的已落地形态，命名偏差见执行日志）。
 *
 * <p>schema 即权限：maxLength/pattern 在形状面收紧，语义上限（窗幅/未来时间/service
 * 范围）在执行器域内判（PrometheusQueryExecutor 同律）。risk 全 R0 只读；
 * additionalProperties=false 由 {@link ToolDefinition} 归一强制。
 */
public final class DirectReadToolCatalog {

    public static final String VERSION = "1";

    public static final String TOOL_INSTANT = "prometheus.instant";
    public static final String TOOL_CATALOG = "prometheus.catalog";
    public static final String TOOL_LABEL_VALUES = "prometheus.label_values";
    public static final String TOOL_RULES = "prometheus.rules";
    public static final String TOOL_LOGS_AGGREGATE = "logs.aggregate";
    public static final String TOOL_CHANGE_DIFF = "change.diff";
    public static final String TOOL_DOCKER_PS = "docker.ps";
    public static final String TOOL_DOCKER_INSPECT = "docker.inspect";
    public static final String TOOL_ALERT_HISTORY = "alert.history";

    private DirectReadToolCatalog() {
    }

    /** 单工具身份（证据类型 = 既有命名法：来源面.查询形状；§一 T01"真实 refs 可回读"） */
    public static SingleToolEvidenceAgent.ToolSpec spec(String toolName, String evidenceType,
            String source) {
        return new SingleToolEvidenceAgent.ToolSpec(toolName, VERSION, evidenceType, source);
    }

    /** promql_instant_query：expr,time → 标量/向量（query_range 的对偶） */
    public static ToolDefinition prometheusInstant(long timeoutMillis, long resultLimitBytes) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "maxLength", 512));
        properties.put("time", Map.of("type", "string", "pattern", "^\\d{1,10}$"));
        return definition(TOOL_INSTANT, properties, List.of("query", "time"),
                timeoutMillis, resultLimitBytes);
    }

    /** metric_catalog_search：match 过滤 → 指标名+type+unit（先目录后查询，治乱猜） */
    public static ToolDefinition prometheusCatalog(long timeoutMillis, long resultLimitBytes) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("match", Map.of("type", "string", "maxLength", 256));
        return definition(TOOL_CATALOG, properties, List.of(), timeoutMillis, resultLimitBytes);
    }

    /** label_values_query：label,selector → 值列表（先发现 label 再拼 PromQL） */
    public static ToolDefinition prometheusLabelValues(long timeoutMillis,
            long resultLimitBytes) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("label", Map.of("type", "string",
                "pattern", "^[a-zA-Z_][a-zA-Z0-9_]{0,63}$"));
        properties.put("match", Map.of("type", "string", "maxLength", 256));
        return definition(TOOL_LABEL_VALUES, properties, List.of("label"),
                timeoutMillis, resultLimitBytes);
    }

    /** alert_rule_lookup：alertname → 规则 expr+annotations（阈值/for/runbook_url，RCA 第一跳） */
    public static ToolDefinition prometheusRules(long timeoutMillis, long resultLimitBytes) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("alertname", Map.of("type", "string", "maxLength", 256));
        return definition(TOOL_RULES, properties, List.of("alertname"),
                timeoutMillis, resultLimitBytes);
    }

    /** log_error_aggregate：service,window → 聚合统计（先聚合后读行，确定性零 LLM） */
    public static ToolDefinition logsAggregate(long timeoutMillis, long resultLimitBytes) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("since", Map.of("type", "string"));
        properties.put("until", Map.of("type", "string"));
        properties.put("service", Map.of("type", "string", "maxLength", 128));
        return definition(TOOL_LOGS_AGGREGATE, properties, List.of("since", "until"),
                timeoutMillis, resultLimitBytes);
    }

    /** change_event_diff：service,window → 窗内变更 + 窗前基线（变更前后 diff 一次给出） */
    public static ToolDefinition changeDiff(long timeoutMillis, long resultLimitBytes) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("since", Map.of("type", "string"));
        properties.put("until", Map.of("type", "string"));
        properties.put("service", Map.of("type", "string", "maxLength", 128));
        return definition(TOOL_CHANGE_DIFF, properties, List.of("since", "until"),
                timeoutMillis, resultLimitBytes);
    }

    /** docker_ps：→ 容器状态清单（渲染只留 allowlist 容器；env 无此调用面） */
    public static ToolDefinition dockerPs(long timeoutMillis, long resultLimitBytes) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("all", Map.of("type", "boolean"));
        return definition(TOOL_DOCKER_PS, properties, List.of(), timeoutMillis, resultLimitBytes);
    }

    /** docker_inspect：container → 状态/重启次数/镜像/env<b>键名</b>（值永不回传，§一只读边界） */
    public static ToolDefinition dockerInspect(long timeoutMillis, long resultLimitBytes) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("container", Map.of("type", "string",
                "pattern", "^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$", "maxLength", 64));
        return definition(TOOL_DOCKER_INSPECT, properties, List.of("container"),
                timeoutMillis, resultLimitBytes);
    }

    /** alert_history_query：alertname/fingerprint + 窗 → 触发/恢复时间线（是否复发/抖动） */
    public static ToolDefinition alertHistory(long timeoutMillis, long resultLimitBytes) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("alertname", Map.of("type", "string", "maxLength", 256));
        properties.put("fingerprint", Map.of("type", "string", "maxLength", 256));
        properties.put("since", Map.of("type", "string"));
        properties.put("until", Map.of("type", "string"));
        return definition(TOOL_ALERT_HISTORY, properties, List.of("since", "until"),
                timeoutMillis, resultLimitBytes);
    }

    private static ToolDefinition definition(String name, Map<String, Object> properties,
            List<String> required, long timeoutMillis, long resultLimitBytes) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return new ToolDefinition(name, VERSION, schema, ToolRisk.R0,
                timeoutMillis, resultLimitBytes);
    }
}
