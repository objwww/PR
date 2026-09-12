package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.DrillJob;
import com.objwww.pr.control.drill.domain.model.DrillTemplate;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.GoldenScenarioRegistry;

import java.util.List;
import java.util.Objects;

/**
 * DR-03 复合注入端口（§7.5 DR-03 卡「执行生命周期」）：按模板 driver 分派到
 * kind 适配器（{@link ArenaChaosDrillInjection} / {@link FlagdDrillInjection}），
 * 公共闸门在分派前统一把守——
 * <ul>
 *   <li>场景未登记 / 靶场不在部署白名单 / 模板未 ready（如实带模板原因，不绕过）/
 *       模板缺 driver / 执行域注册表缺场景 / 目录与注册表 driver 漂移 /
 *       未知驱动 → NOT_PERFORMED + 具体卡因（确定零副作用，INJECTING→FAILED
 *       合法，与 {@link DrillPrecheck} 同卡因前缀便于对账）；</li>
 *   <li>分派后的三态由 kind 适配器如实判定（异常映射见
 *       {@link DrillInjectionFailures}）——严禁把 UNKNOWN 当 PERFORMED，
 *       严禁假成功。</li>
 * </ul>
 *
 * <p>GT 面纪律（§7.3「GT/预期根因/注入控制细节仅评测执行域可读」）：本端口运行在
 * 评测执行身份所在的 worker 内，可读 {@link GoldenScenarioRegistry} 取执行参数；
 * 回执（{@link DrillInjectionReceipt}）只承载执行身份面，不回传任何 GT 字段。
 */
public final class CompositeDrillInjection implements DrillInjectionPort {

    private final DrillTemplateCatalog catalog;
    private final GoldenScenarioRegistry registry;
    private final List<String> allowedEnvs;
    private final ArenaChaosDrillInjection arena;
    private final FlagdDrillInjection flagd;

    public CompositeDrillInjection(DrillTemplateCatalog catalog,
                                   GoldenScenarioRegistry registry,
                                   List<String> allowedEnvs,
                                   ArenaChaosDrillInjection arena,
                                   FlagdDrillInjection flagd) {
        this.catalog = Objects.requireNonNull(catalog);
        this.registry = Objects.requireNonNull(registry);
        this.allowedEnvs = List.copyOf(allowedEnvs);
        this.arena = Objects.requireNonNull(arena);
        this.flagd = Objects.requireNonNull(flagd);
    }

    @Override
    public Outcome inject(DrillJob job) {
        DrillTemplate template = catalog.byScenarioId(job.scenarioId()).orElse(null);
        if (template == null) {
            return Outcome.notPerformed("SCENARIO_KNOWN: 场景未注册于模板目录"
                    + "（确定零副作用）: " + job.scenarioId());
        }
        if (!allowedEnvs.contains(job.targetEnv())) {
            return Outcome.notPerformed("TARGET_ENV_WHITELIST: 靶场不在部署白名单内"
                    + "（篡改面拒绝，DU04；确定零副作用）: " + job.targetEnv());
        }
        if (!template.execution().ready()) {
            return Outcome.notPerformed("EXECUTION_READY: 模板未开放执行"
                    + "（确定零副作用）——" + template.execution().reason());
        }
        if (template.driver() == null || template.driver().isBlank()) {
            return Outcome.notPerformed("DRIVER_KNOWN: 模板缺 driver"
                    + "（确定零副作用）: " + job.scenarioId());
        }
        GoldenCase golden;
        try {
            golden = registry.byScenarioId(job.scenarioId());
        } catch (RuntimeException e) {
            return Outcome.notPerformed("SCENARIO_KNOWN: 执行域注册表缺场景"
                    + "（确定零副作用）: " + e.getMessage());
        }
        if (!template.driver().equals(golden.driver())) {
            // 发布 DTO 目录与执行域注册表漂移 = 配置缺陷，拒绝执行不猜
            return Outcome.notPerformed("DRIVER_KNOWN: 目录与注册表 driver 漂移"
                    + "（确定零副作用）: 目录=" + template.driver()
                    + " 注册表=" + golden.driver());
        }
        return switch (template.driver()) {
            case "ArenaChaosScenarioDriver" -> arena.inject(job, template, golden);
            case "FlagdScenarioDriver" -> flagd.inject(job, template, golden);
            default -> Outcome.notPerformed("DRIVER_KNOWN: 无注入接线的驱动"
                    + "（确定零副作用）: " + template.driver());
        };
    }
}
