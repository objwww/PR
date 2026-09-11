package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolInvoker;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;

/**
 * 受控直查 Agent（EN-05，§一 P0 真实工具族同构面）：调用/记账/证据纪律全部在
 * {@link SingleToolEvidenceAgent} 基座（预算 TOOL_CALL 硬闸 + 账本 PENDING 先行 +
 * Gateway 唯一咽喉 + 证据落库 + 熔断门），本类零新增逻辑——每个<b>实例</b>仍恰为
 * 一工具（构造期 profile.toolAllowlist 单工具校验不破），工具身份来自
 * {@link DirectReadToolCatalog}。装配形态见 MetricsAgent/LogsAgent/ChangeAgent 的
 * 每类一工具先例；P0 工具族共用本类以免九个子类样板。
 */
public class DirectReadToolAgent extends SingleToolEvidenceAgent {

    public DirectReadToolAgent(AgentProfile profile, ToolSpec spec, ToolRegistry registry,
            ToolInvoker gateway, EvidenceRepository evidence,
            RcaToolInvocationLedger ledger, ObjectMapper mapper) {
        super(profile, spec, registry, gateway, evidence, ledger, mapper);
    }

    /** EX-A1 全参形态（生产装配唯一入口）：预算门 + 熔断门直通基座 */
    public DirectReadToolAgent(AgentProfile profile, ToolSpec spec, ToolRegistry registry,
            ToolInvoker gateway, EvidenceRepository evidence,
            RcaToolInvocationLedger ledger, ObjectMapper mapper,
            com.objwww.pr.control.alert.application.RunBudgetGate budgetGate,
            com.objwww.pr.control.alert.domain.budget.DoomLoopGuard doomLoopGuard) {
        super(profile, spec, registry, gateway, evidence, ledger, mapper,
                budgetGate, doomLoopGuard);
    }
}
