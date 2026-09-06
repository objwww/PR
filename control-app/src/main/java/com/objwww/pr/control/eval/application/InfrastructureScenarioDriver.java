package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.GoldenCase;

/**
 * 基础设施场景驱动（M3-17 预留位）：首批 5 场景（flagd×2 + 靶场×3）未用。
 * 注册表出现 driver: InfrastructureScenarioDriver 的场景但未登记基础设施注入面时，
 * fail-fast 拒绝——不做任何"顺手"注入（INV-AM3-4 评测流量边界）。
 */
public final class InfrastructureScenarioDriver implements ScenarioDriver {

    @Override
    public ActivationReceipt activate(GoldenCase golden, int roundNo) {
        throw new IllegalStateException(
                "InfrastructureScenarioDriver 预留未实现（首批 5 场景不使用），拒绝注入: "
                        + golden.scenarioId());
    }

    @Override
    public RecoveryReceipt deactivate(GoldenCase golden, ActivationReceipt receipt) {
        throw new IllegalStateException(
                "InfrastructureScenarioDriver 预留未实现，无恢复路径: " + golden.scenarioId());
    }
}
