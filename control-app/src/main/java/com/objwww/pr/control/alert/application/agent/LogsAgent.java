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
 * Logs Agent（AM4 M4-28；EX-B2 真实源换绑）：只做一种 R0 日志查询。数据面 =
 * {@code LogQueryExecutor} 真查 Loki（盘点门签字后换绑，见 docs/告警-EXB2-logs真实源.md）；
 * 无数据三态 EMPTY/SOURCE_UNAVAILABLE/QUERY_FAILED 各有确定结局，禁止 fixture 顶替。
 */
public class LogsAgent extends SingleToolEvidenceAgent {

    public static final String TOOL_NAME = "logs.query";
    public static final String TOOL_VERSION = "1";
    public static final String EVIDENCE_TYPE = "logs.query";
    public static final String SOURCE = "logs";

    /** 日志查询（since/until epoch 秒或 RFC3339，原样透传；源侧契约随冻结数据源演进） */
    public record LogsQuery(String since, String until) {
    }

    public LogsAgent(AgentProfile profile, ToolRegistry registry, ToolInvoker gateway,
            EvidenceRepository evidence, RcaToolInvocationLedger ledger, ObjectMapper mapper) {
        super(profile, new ToolSpec(TOOL_NAME, TOOL_VERSION, EVIDENCE_TYPE, SOURCE),
                registry, gateway, evidence, ledger, mapper);
    }

    /** EX-A1 全参形态（生产装配唯一入口）：预算门 + 熔断门直通基座 */
    public LogsAgent(AgentProfile profile, ToolRegistry registry, ToolInvoker gateway,
            EvidenceRepository evidence, RcaToolInvocationLedger ledger, ObjectMapper mapper,
            com.objwww.pr.control.alert.application.RunBudgetGate budgetGate,
            com.objwww.pr.control.alert.domain.budget.DoomLoopGuard doomLoopGuard) {
        super(profile, new ToolSpec(TOOL_NAME, TOOL_VERSION, EVIDENCE_TYPE, SOURCE),
                registry, gateway, evidence, ledger, mapper, budgetGate, doomLoopGuard);
    }

    public AgentResult investigate(CallContext ctx, LogsQuery query) {
        return investigate(ctx, argsOf(query));
    }

    public static Map<String, Object> argsOf(LogsQuery query) {
        LinkedHashMap<String, Object> args = new LinkedHashMap<>();
        args.put("since", query.since());
        args.put("until", query.until());
        return args;
    }

    /** 日志查询工具定义（Loki 真源；service 可选，allowlist 面 executor 域内判） */
    public static ToolDefinition toolDefinition(long timeoutMillis, long resultLimitBytes) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("since", Map.of("type", "string"));
        properties.put("until", Map.of("type", "string"));
        properties.put("service", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("since", "until"));
        return new ToolDefinition(TOOL_NAME, TOOL_VERSION, schema, ToolRisk.R0,
                timeoutMillis, resultLimitBytes);
    }
}
