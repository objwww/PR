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
 * Change Agent（AM4 M4-29）：只做一种 R0 变更记录查询（只读，无写权限——
 * 写面工具未进策略允许集，越权调用被控制面 POLICY_DENIED 终止族拒绝）。
 * 数据源限制同 M4-28（评审 P0-7）：replay fixture 限定，不宣称 Live。
 */
public class ChangeAgent extends SingleToolEvidenceAgent {

    public static final String TOOL_NAME = "change.query";
    public static final String TOOL_VERSION = "1";
    public static final String EVIDENCE_TYPE = "change.query";
    public static final String SOURCE = "change";

    /** 变更查询（since/until 时间窗，原样透传；源侧契约随冻结数据源演进） */
    public record ChangeQuery(String since, String until) {
    }

    public ChangeAgent(AgentProfile profile, ToolRegistry registry, ToolGateway gateway,
            EvidenceRepository evidence, RcaToolInvocationLedger ledger, ObjectMapper mapper) {
        super(profile, new ToolSpec(TOOL_NAME, TOOL_VERSION, EVIDENCE_TYPE, SOURCE),
                registry, gateway, evidence, ledger, mapper);
    }

    public AgentResult investigate(CallContext ctx, ChangeQuery query) {
        return investigate(ctx, argsOf(query));
    }

    public static Map<String, Object> argsOf(ChangeQuery query) {
        LinkedHashMap<String, Object> args = new LinkedHashMap<>();
        args.put("since", query.since());
        args.put("until", query.until());
        return args;
    }

    /** 变更查询工具定义（只读 replay 数据面；R0 显式） */
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
