package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.DrillJob;
import com.objwww.pr.control.drill.domain.model.DrillTemplate;
import com.objwww.pr.control.eval.application.FlagdScenarioDriver;
import com.objwww.pr.control.eval.application.ScenarioDriver;
import com.objwww.pr.control.eval.domain.GoldenCase;

import java.util.Objects;

/**
 * DR-03 flagd 注入适配（§7.5 DR-03 + §7.4 Flagd 段）：复用
 * {@link FlagdScenarioDriver}——defaultVariant 单键精确切换（BA-19 纪律），
 * 写入前读实际当前值/代际并落恢复台账（DR-05 四参装配面；条件恢复/冲突不覆盖
 * 归 deactivate 与 {@link FlagdRestoreSweeper}，不在本注入面）。
 *
 * <p>三态：activate 返回回执 → PERFORMED（回执身份 = 模板 scenarioId + 写入
 * 代际面，flagd 无每轮派生 id——flag 本身即资源，并发互斥由同靶场占位与条件
 * 恢复世代令牌保障）；缺 injection 参数在任何写入之前拒绝 → NOT_PERFORMED；
 * 写入超时/5xx 等无法判定 → UNKNOWN 经 {@link DrillInjectionFailures}，
 * 对账锚 = scenarioId/flag。
 */
public final class FlagdDrillInjection {

    private final FlagdScenarioDriver driver;

    public FlagdDrillInjection(FlagdScenarioDriver driver) {
        this.driver = Objects.requireNonNull(driver);
    }

    DrillInjectionPort.Outcome inject(DrillJob job, DrillTemplate template,
                                      GoldenCase golden) {
        if (golden.injection() == null) {
            return DrillInjectionPort.Outcome.notPerformed(
                    "INJECTION_PARAMS: flagd 场景缺 injection 参数"
                            + "（flag/variant/baseline_variant；确定零副作用）: "
                            + golden.scenarioId());
        }
        String fixedIdentity = golden.scenarioId() + "/flag=" + golden.injection().flag();
        try {
            ScenarioDriver.ActivationReceipt receipt = driver.activate(golden, 1);
            return DrillInjectionPort.Outcome.performed(
                    DrillInjectionReceipt.json(job, "FlagdScenarioDriver", receipt, null));
        } catch (RuntimeException e) {
            return DrillInjectionFailures.from(e, fixedIdentity);
        }
    }
}
