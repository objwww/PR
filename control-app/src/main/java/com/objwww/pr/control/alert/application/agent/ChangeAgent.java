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
 * Change Agent（AM4 M4-29）：只做一种 R0 变更记录查询（只读，无写权限——
 * 写面工具未进策略允许集，越权调用被控制面 POLICY_DENIED 终止族拒绝）。
 * EX-B1 起数据源 = V40 change_event 真实变更源（config 激活同事务 + 部署脚本
 * 落档，Agent 保持只读）；回放/测试面见 test 资源夹具与 ChangeQueryExecutorTest。
 */
public class ChangeAgent extends SingleToolEvidenceAgent {

    public static final String TOOL_NAME = "change.query";
    public static final String TOOL_VERSION = "1";
    public static final String EVIDENCE_TYPE = "change.query";
    public static final String SOURCE = "change";

    /** 变更查询（since/until 时间窗，原样透传；源侧契约随冻结数据源演进） */
    public record ChangeQuery(String since, String until) {
    }

    public ChangeAgent(AgentProfile profile, ToolRegistry registry, ToolInvoker gateway,
            EvidenceRepository evidence, RcaToolInvocationLedger ledger, ObjectMapper mapper) {
        super(profile, new ToolSpec(TOOL_NAME, TOOL_VERSION, EVIDENCE_TYPE, SOURCE),
                registry, gateway, evidence, ledger, mapper);
    }

    /** EX-A1 全参形态（生产装配唯一入口）：预算门 + 熔断门直通基座 */
    public ChangeAgent(AgentProfile profile, ToolRegistry registry, ToolInvoker gateway,
            EvidenceRepository evidence, RcaToolInvocationLedger ledger, ObjectMapper mapper,
            com.objwww.pr.control.alert.application.RunBudgetGate budgetGate,
            com.objwww.pr.control.alert.domain.budget.DoomLoopGuard doomLoopGuard) {
        super(profile, new ToolSpec(TOOL_NAME, TOOL_VERSION, EVIDENCE_TYPE, SOURCE),
                registry, gateway, evidence, ledger, mapper, budgetGate, doomLoopGuard);
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

    /** 变更查询工具定义（只读真实变更源 EX-B1；service 可选，缺省 control-app，allowlist 在 executor 侧判） */
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
