package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.model.EvalLaunchPlan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * PAGE-03 发起能力闸门（评测执行模式与可配置字段的"实际支持范围"单一裁决点）：
 * 本环境只接受执行契约真实闭合的请求——未实现隔离执行器的模式、不能落到执行面的
 * 覆盖项/限额，一律在入队前（{@link EvalCommandService#launch}）与 worker 领取后
 * （{@link EvalLaunchExecutor#execute}）两处拒绝，不允许"元数据生效、执行被静默忽略"。
 *
 * <p>当前支持范围（诚实边界，与 EvalLaunchPlan 既有偏差登记一致）：
 * <ul>
 *   <li>mode 仅 L（真实模型 + 靶场的既有执行路径）；E（确定性回放）与 B（冻结输入
 *       真实模型对照）的隔离执行器未实现——放行会让 E/B 走进故障驱动现场，违反页面
 *       "无现场副作用/不触碰靶场"承诺；</li>
 *   <li>datasetVersion 仅装配的部署版本（案例实际来自装配 registry，动态版本解析未
 *       实现——放行会让结果的数据集身份与实际输入不一致）；</li>
 *   <li>model/promptVersion 仅接受 null（覆盖项只改元数据名称，不切换实际执行内容
 *       ——放行会让配置身份造假）；</li>
 *   <li>budgetMaxTokens/deadlineSeconds 仅接受 null（执行面强制未接线，放行即
 *       "持久化但从不生效"的假约束）；</li>
 *   <li>maxConcurrency 固定 1（多 worker 并发上限未实现）；</li>
 *   <li>roundsPerScenario ≤ {@link #maxRounds}（业务上限防误填超大批）。</li>
 * </ul>
 * 后续开放任一项时：先补齐对应执行面实现，再改本闸门装配——闸门描述即能力读面
 * （/api/eval/launch-capability），前端从同一数据禁用不可用项。
 */
public final class EvalLaunchGate {

    /** 拒绝异常（400 面；携带支持范围供调用方透出）——是否落 REJECTED 由调用方定 */
    public static final class EvalLaunchUnsupportedException extends IllegalArgumentException {
        private final String code;
        private final transient Map<String, Object> supported;

        public EvalLaunchUnsupportedException(String code, String message,
                                              Map<String, Object> supported) {
            super(message);
            this.code = code;
            this.supported = supported;
        }

        public String code() {
            return code;
        }

        public Map<String, Object> supported() {
            return supported;
        }
    }

    private final Set<String> modes;
    private final Set<String> datasetVersions;
    private final int maxConcurrency;
    private final int maxRounds;
    private final boolean modelOverrideSupported;
    private final boolean promptOverrideSupported;
    private final boolean budgetSupported;
    private final boolean deadlineSupported;
    private final boolean launchEnabled;

    /** P6-G8 panel 值域（SMOKE 快速回归子集；过滤语义注册表驱动，值域全局唯一——
     *  非法值入队前拒绝，防止"计划写 SMOKE、执行跑全量"的身份错位） */
    public static final Set<String> SUPPORTED_PANELS = Set.of("SMOKE");

    /** 全支持面构造（测试对照用）；生产装配用 {@link #closed(Set, String, int, int)} */
    public EvalLaunchGate(Set<String> modes, Set<String> datasetVersions, int maxConcurrency,
                          int maxRounds, boolean modelOverrideSupported,
                          boolean promptOverrideSupported, boolean budgetSupported,
                          boolean deadlineSupported, boolean launchEnabled) {
        if (modes == null || modes.isEmpty()) {
            throw new IllegalArgumentException("modes 不得为空（至少一个已实现模式）");
        }
        if (datasetVersions == null || datasetVersions.isEmpty()) {
            throw new IllegalArgumentException("datasetVersions 不得为空");
        }
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency 必须 ≥1");
        }
        if (maxRounds < 1) {
            throw new IllegalArgumentException("maxRounds 必须 ≥1");
        }
        this.modes = Set.copyOf(modes);
        this.datasetVersions = Set.copyOf(datasetVersions);
        this.maxConcurrency = maxConcurrency;
        this.maxRounds = maxRounds;
        this.modelOverrideSupported = modelOverrideSupported;
        this.promptOverrideSupported = promptOverrideSupported;
        this.budgetSupported = budgetSupported;
        this.deadlineSupported = deadlineSupported;
        this.launchEnabled = launchEnabled;
    }

    /** 生产装配：执行隔离未实现的字段全闭（model/prompt 覆盖与 budget/deadline 不支持），
     *  发起面开放 */
    public static EvalLaunchGate closed(Set<String> modes, String datasetVersion,
                                        int maxConcurrency, int maxRounds) {
        return new EvalLaunchGate(modes, Set.of(datasetVersion), maxConcurrency, maxRounds,
                false, false, false, false, true);
    }

    /** SAFE-02 生产装配：同 closed 但发起面整体关闭——L 与 drill 共享环境互斥未落地
     *  前，服务端拒绝一切发起（不是警告）；开放需先落地共享占用（评审资源与恢复包） */
    public static EvalLaunchGate launchDisabled(Set<String> modes, String datasetVersion,
                                                int maxConcurrency, int maxRounds) {
        return new EvalLaunchGate(modes, Set.of(datasetVersion), maxConcurrency, maxRounds,
                false, false, false, false, false);
    }

    /** 入队前 / 领取后统一校验：不支持即抛 {@link EvalLaunchUnsupportedException} */
    public void check(EvalLaunchPlan plan) {
        Objects.requireNonNull(plan, "plan 不得为 null");
        if (!launchEnabled) {
            // SAFE-02：发起面整体关闭（L 与 drill 共享环境互斥未落地）——两入口
            // （命令面 + worker 领取复验）同源拒绝，而非警告；EST-05 面零注入
            throw new EvalLaunchUnsupportedException("LAUNCH_DISABLED",
                    "评测发起当前已关闭：L 与演练共享环境互斥未落地（共享占用协议交付前"
                            + "不接受任何发起），已支持的能力面仅只读查询与预检", describe());
        }
        if (!modes.contains(plan.mode())) {
            throw new EvalLaunchUnsupportedException("MODE_NOT_SUPPORTED",
                    "模式 " + plan.mode() + " 在当前环境未实现隔离执行，已拒绝（支持："
                            + String.join("/", modes) + "）", describe());
        }
        if (!datasetVersions.contains(plan.datasetVersion())) {
            throw new EvalLaunchUnsupportedException("DATASET_VERSION_NOT_SUPPORTED",
                    "数据集版本 " + plan.datasetVersion() + " 不是当前部署版本（支持："
                            + String.join("/", datasetVersions)
                            + "）；动态版本解析未开放，请选择已部署版本", describe());
        }
        if (plan.model() != null && !modelOverrideSupported) {
            throw new EvalLaunchUnsupportedException("MODEL_OVERRIDE_NOT_SUPPORTED",
                    "自定义 model 未开放：覆盖项不会切换实际执行内容，请留空沿用部署模型",
                    describe());
        }
        if (plan.promptVersion() != null && !promptOverrideSupported) {
            throw new EvalLaunchUnsupportedException("PROMPT_OVERRIDE_NOT_SUPPORTED",
                    "自定义 promptVersion 未开放：请留空沿用部署版本", describe());
        }
        if (plan.budgetMaxTokens() != null && !budgetSupported) {
            throw new EvalLaunchUnsupportedException("BUDGET_NOT_SUPPORTED",
                    "预算（budgetMaxTokens）执行面强制未接线，本期不接受非空值（不落假约束）",
                    describe());
        }
        if (plan.deadlineSeconds() != null && !deadlineSupported) {
            throw new EvalLaunchUnsupportedException("DEADLINE_NOT_SUPPORTED",
                    "截止时间（deadlineSeconds）执行面强制未接线，本期不接受非空值（不落假约束）",
                    describe());
        }
        int concurrency = plan.maxConcurrency() == null ? 1 : plan.maxConcurrency();
        if (concurrency > maxConcurrency) {
            throw new EvalLaunchUnsupportedException("CONCURRENCY_NOT_SUPPORTED",
                    "并发 " + concurrency + " 超出当前环境支持上限 " + maxConcurrency
                            + "（多 worker 并发未开放）", describe());
        }
        if (plan.roundsPerScenario() != null && plan.roundsPerScenario() > maxRounds) {
            throw new EvalLaunchUnsupportedException("ROUNDS_OUT_OF_RANGE",
                    "重复次数 " + plan.roundsPerScenario() + " 超出上限 " + maxRounds,
                    describe());
        }
        if (plan.panel() != null && !SUPPORTED_PANELS.contains(plan.panel())) {
            throw new EvalLaunchUnsupportedException("PANEL_NOT_SUPPORTED",
                    "panel " + plan.panel() + " 不在支持值域（支持："
                            + String.join("/", SUPPORTED_PANELS)
                            + "）；空 = 全量原表", describe());
        }
    }

    /** SAFE-02/FUP-01 能力位读口（CLI once 入口与命令面/worker 同源闭面判定用） */
    public boolean launchEnabled() {
        return launchEnabled;
    }

    /** 能力读面（/api/eval/launch-capability 与 400 应答的 supported 同源；只读） */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("launchEnabled", launchEnabled);
        out.put("modes", List.copyOf(modes));
        out.put("datasetVersions", List.copyOf(datasetVersions));
        out.put("maxConcurrency", maxConcurrency);
        out.put("maxRoundsPerScenario", maxRounds);
        out.put("panels", List.copyOf(SUPPORTED_PANELS));
        out.put("modelOverride", modelOverrideSupported);
        out.put("promptOverride", promptOverrideSupported);
        out.put("budgetMaxTokens", budgetSupported);
        out.put("deadlineSeconds", deadlineSupported);
        return java.util.Collections.unmodifiableMap(out);
    }
}
