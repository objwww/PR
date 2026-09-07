package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolInvoker;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolRisk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Metrics Agent（AM4 M4-27）：只做一种 R0 指标查询（Prometheus query_range）——
 * 调用/记账/证据纪律全部在 {@link SingleToolEvidenceAgent} 基座，本类只固化
 * Prometheus 的工具身份、参数四元组与查询 API。
 */
public class MetricsAgent extends SingleToolEvidenceAgent {

    public static final String TOOL_NAME = "prometheus.query";
    public static final String TOOL_VERSION = "1";
    public static final String EVIDENCE_TYPE = "metrics.query_range";
    public static final String SOURCE = "prometheus";

    /** R0 查询四元组（start/end epoch 秒或 RFC3339，step 如 "30s"，原样透传） */
    public record MetricsQuery(String expr, String start, String end, String step) {
    }

    public MetricsAgent(AgentProfile profile, ToolRegistry registry, ToolInvoker gateway,
            EvidenceRepository evidence, RcaToolInvocationLedger ledger, ObjectMapper mapper) {
        super(profile, new ToolSpec(TOOL_NAME, TOOL_VERSION, EVIDENCE_TYPE, SOURCE),
                registry, gateway, evidence, ledger, mapper);
    }

    /** 一次 R0 指标查询 → 证据（或 NO_DATA / FAILED） */
    public AgentResult investigate(CallContext ctx, MetricsQuery query) {
        return investigate(ctx, argsOf(query));
    }

    /** Gateway 同形的参数映射（契约锚点：恰为 schema 声明的四参） */
    public static Map<String, Object> argsOf(MetricsQuery query) {
        LinkedHashMap<String, Object> args = new LinkedHashMap<>();
        args.put("query", query.expr());
        args.put("start", query.start());
        args.put("end", query.end());
        args.put("step", query.step());
        return args;
    }

    /** Prometheus 工具定义（R0 必须显式声明——null 缺省从严 R3 即 VALIDATE_ONLY） */
    public static ToolDefinition toolDefinition(long timeoutMillis, long resultLimitBytes) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string"));
        properties.put("start", Map.of("type", "string"));
        properties.put("end", Map.of("type", "string"));
        properties.put("step", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query", "start", "end", "step"));
        return new ToolDefinition(TOOL_NAME, TOOL_VERSION, schema, ToolRisk.R0,
                timeoutMillis, resultLimitBytes);
    }
}
