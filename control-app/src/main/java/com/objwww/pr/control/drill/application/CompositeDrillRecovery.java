package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.DrillJob;
import com.objwww.pr.control.drill.domain.model.DrillTemplate;
import com.objwww.pr.control.eval.application.AlertProbe;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.GoldenScenarioRegistry;

import java.util.Objects;

/**
 * DR-04 复合恢复/核验端口（对称 {@link CompositeDrillInjection}；§7.5 DR-04 卡
 * 「停止/恢复执行接线」）：
 * <ul>
 *   <li><b>recover</b>：按模板 driver 分派到 kind 适配器（{@link ArenaChaosDrillRecovery} /
 *       {@link FlagdDrillRecovery}）。分派前只做确定性配置闸门（场景未登记/注册表缺
 *       场景/目录与注册表 driver 漂移/无恢复接线的驱动 → FAILED 配置缺陷，确定不可
 *       恢复）；不复验 launch 能力位（恢复是收场方向，能力关闭只挡新注入发起）、
 *       不复验靶场白名单（作业受理时已把守，重开配置不得反噬在场作业的恢复责任）；</li>
 *   <li><b>verify</b>：两 kind 共用同一核验判据 = 模板声明的症状码全部 resolved
 *       （{@link AlertProbe#awaitAllResolved}，单次探针预算 = 模板
 *       recovery.probeSeconds——有界不堵轮询，残留 firing/探针暂不可读 = PENDING
 *       下拍重试，上限归 worker 恢复窗口截止）；模板未声明症状码 = 核验面为空，
 *       如实 VERIFIED（不伪造核验动作）；</li>
 *   <li>GT 面纪律与注入对称：本端口运行在评测执行身份所在的 worker 内，可读
 *       {@link GoldenScenarioRegistry} 取执行参数，不回传任何 GT 字段。</li>
 * </ul>
 */
public final class CompositeDrillRecovery implements DrillRecoveryPort {

    private final DrillTemplateCatalog catalog;
    private final GoldenScenarioRegistry registry;
    private final ArenaChaosDrillRecovery arena;
    private final FlagdDrillRecovery flagd;
    private final AlertProbe alertProbe;

    public CompositeDrillRecovery(DrillTemplateCatalog catalog,
                                  GoldenScenarioRegistry registry,
                                  ArenaChaosDrillRecovery arena,
                                  FlagdDrillRecovery flagd,
                                  AlertProbe alertProbe) {
        this.catalog = Objects.requireNonNull(catalog);
        this.registry = Objects.requireNonNull(registry);
        this.arena = Objects.requireNonNull(arena);
        this.flagd = Objects.requireNonNull(flagd);
        this.alertProbe = Objects.requireNonNull(alertProbe);
    }

    @Override
    public RecoverOutcome recover(DrillJob job) {
        DrillTemplate template = catalog.byScenarioId(job.scenarioId()).orElse(null);
        if (template == null) {
            return RecoverOutcome.failed("SCENARIO_KNOWN: 场景未注册于模板目录"
                    + "（配置缺陷，确定不可恢复）: " + job.scenarioId());
        }
        if (template.driver() == null || template.driver().isBlank()) {
            return RecoverOutcome.failed("DRIVER_KNOWN: 模板缺 driver"
                    + "（配置缺陷，确定不可恢复）: " + job.scenarioId());
        }
        GoldenCase golden;
        try {
            golden = registry.byScenarioId(job.scenarioId());
        } catch (RuntimeException e) {
            return RecoverOutcome.failed("SCENARIO_KNOWN: 执行域注册表缺场景"
                    + "（配置缺陷，确定不可恢复）: " + e.getMessage());
        }
        if (!template.driver().equals(golden.driver())) {
            return RecoverOutcome.failed("DRIVER_KNOWN: 目录与注册表 driver 漂移"
                    + "（配置缺陷，确定不可恢复）: 目录=" + template.driver()
                    + " 注册表=" + golden.driver());
        }
        return switch (template.driver()) {
            case "ArenaChaosScenarioDriver" -> arena.recover(job, template, golden);
            case "FlagdScenarioDriver" -> flagd.recover(job, template, golden);
            default -> RecoverOutcome.failed("DRIVER_KNOWN: 无恢复接线的驱动"
                    + "（配置缺陷，确定不可恢复）: " + template.driver());
        };
    }

    @Override
    public VerifyOutcome verify(DrillJob job) {
        DrillTemplate template = catalog.byScenarioId(job.scenarioId()).orElse(null);
        if (template == null) {
            return VerifyOutcome.failed("SCENARIO_KNOWN: 场景未注册于模板目录"
                    + "（配置缺陷，确定不可核验）: " + job.scenarioId());
        }
        if (template.symptomCodes().isEmpty()) {
            return VerifyOutcome.verified(
                    "no_symptom_codes: 模板未声明症状码——核验面为空，如实放行");
        }
        boolean resolved;
        try {
            resolved = alertProbe.awaitAllResolved(job.scenarioId(),
                    template.recovery().probeSeconds());
        } catch (RuntimeException e) {
            return VerifyOutcome.pending("verify_probe_error: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "（探针暂不可读，下拍重试）");
        }
        return resolved
                ? VerifyOutcome.verified("alerts_resolved: 期望症状码全部 resolved")
                : VerifyOutcome.pending("alerts_still_firing: 期望症状码仍有残留 firing"
                        + "（下拍重试，上限归恢复窗口截止）");
    }
}
