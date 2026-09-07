package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolGateway;
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
 * Logs Agent（AM4 M4-28）：只做一种 R0 日志查询。数据源限制（评审 P0-7）：当前无
 * 冻结的实时日志源，数据面 = replay fixture（{@code ReplayToolExecutor}），不得宣称
 * Live E2E；Live 需先冻结数据源清单+只读凭证+ToolDefinition+部署契约。
 */
public class LogsAgent extends SingleToolEvidenceAgent {

    public static final String TOOL_NAME = "logs.query";
    public static final String TOOL_VERSION = "1";
    public static final String EVIDENCE_TYPE = "logs.query";
    public static final String SOURCE = "logs";

    /** 日志查询（since/until epoch 秒或 RFC3339，原样透传；源侧契约随冻结数据源演进） */
    public record LogsQuery(String since, String until) {
    }

    public LogsAgent(AgentProfile profile, ToolRegistry registry, ToolGateway gateway,
            EvidenceRepository evidence, RcaToolInvocationLedger ledger, ObjectMapper mapper) {
        super(profile, new ToolSpec(TOOL_NAME, TOOL_VERSION, EVIDENCE_TYPE, SOURCE),
                registry, gateway, evidence, ledger, mapper);
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

    /** 日志工具定义（replay 数据面；R0 显式） */
    public static ToolDefinition toolDefinition(long timeoutMillis, long resultLimitBytes) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("since", Map.of("type", "string"));
        properties.put("until", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("since", "until"));
        return new ToolDefinition(TOOL_NAME, TOOL_VERSION, schema, ToolRisk.R0,
                timeoutMillis, resultLimitBytes);
    }
}
