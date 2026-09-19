package com.objwww.pr.control.drill.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * DR-02 演练场景模板（发布展示 DTO 的域形；§7.1"复用版本化场景模板；发布展示 DTO，
 * 不把完整答案注册表交给调查 Agent"）：
 * <ul>
 *   <li>只含公开面：id/名称/类型/故障源/驱动/症状（可观测面）/影响范围/时间参数/
 *       参数白名单；expected_root_cause 等 GT 面不属于本对象，目录文件禁止回填；</li>
 *   <li>execution.ready=false + reason = 如实不可用（§7.2"其余标'不可用'并给原因"），
 *       不展示假按钮；</li>
 *   <li>参数白名单服务端强制（DU04）：durationSeconds 区间、trafficScale 枚举、
 *       关联评测版本可选——不接收服务器 IP/任意 SQL/shell。</li>
 * </ul>
 */
public record DrillTemplate(
        String scenarioId,
        String name,
        String scenarioType,
        String faultSource,
        String driver,
        String chaosFamily,
        String target,
        List<String> symptomCodes,
        String symptomDisplay,
        String impact,
        Timing timing,
        ParamWhitelist params,
        Execution execution,
        Recovery recovery) {

    /** 时间参数（对齐 eval-scenarios.yml timing 块；后端据此计算 TTL/预热/恢复窗口） */
    public record Timing(int preheatSeconds, int holdSeconds, int maxFiringWaitSeconds,
                         int maxResolvedWaitSeconds, int cleanupTimeoutSeconds) {

        public Timing {
            if (preheatSeconds < 0 || holdSeconds < 0 || maxFiringWaitSeconds < 0
                    || maxResolvedWaitSeconds < 0 || cleanupTimeoutSeconds < 0) {
                throw new IllegalArgumentException("时间参数不得为负");
            }
        }
    }

    /** 受限参数白名单（§7.2：后端计算实际 TTL 与窗口，前端不各算一套） */
    public record ParamWhitelist(int durationDefaultSeconds, int durationMinSeconds,
                                 int durationMaxSeconds, List<String> trafficScales,
                                 boolean linkedEvalVersionAllowed) {

        public ParamWhitelist {
            if (durationMinSeconds <= 0 || durationMaxSeconds < durationMinSeconds
                    || durationDefaultSeconds < durationMinSeconds
                    || durationDefaultSeconds > durationMaxSeconds) {
                throw new IllegalArgumentException("duration 白名单区间非法");
            }
            trafficScales = trafficScales == null ? List.of() : List.copyOf(trafficScales);
            if (trafficScales.isEmpty()) {
                throw new IllegalArgumentException("trafficScales 至少一档");
            }
        }
    }

    /** 可执行性（如实面）：ready=false 时 reason 必给——不展示假按钮 */
    public record Execution(boolean ready, String reason) {

        public Execution {
            if (!ready && (reason == null || reason.isBlank())) {
                throw new IllegalArgumentException("execution.ready=false 必带原因");
            }
        }
    }

    /**
     * DR-04 恢复/核验参数（模板 recovery 块；缺省值保守）：
     * probeSeconds = 单拍核验探针预算（有界，不堵 worker 轮询）；
     * deadlineSeconds = 进入 RECOVERING/VERIFYING 起的恢复+核验总上限——
     * 超上限未收口 = RECOVERY_FAILED 诚实占位（重试不许死循环）。
     */
    public record Recovery(int probeSeconds, int deadlineSeconds) {

        /** 单拍核验探针预算缺省（秒）：小于一轮告警轮询量级，不堵 poll=5s 的 worker 循环 */
        public static final int DEFAULT_PROBE_SECONDS = 10;

        public Recovery {
            if (probeSeconds <= 0 || deadlineSeconds <= 0) {
                throw new IllegalArgumentException("恢复参数必须为正");
            }
        }

        /** 模板未声明 recovery 块时的缺省：截止 = maxResolvedWait + cleanupTimeout */
        public static Recovery defaulted(Timing timing) {
            Objects.requireNonNull(timing, "timing");
            return new Recovery(DEFAULT_PROBE_SECONDS, Math.max(1,
                    timing.maxResolvedWaitSeconds() + timing.cleanupTimeoutSeconds()));
        }
    }

    public DrillTemplate {
        Objects.requireNonNull(scenarioId, "scenarioId");
        if (scenarioId.isBlank()) {
            throw new IllegalArgumentException("scenarioId 不得为 blank");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(timing, "timing");
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(recovery, "recovery");
        symptomCodes = symptomCodes == null ? List.of() : List.copyOf(symptomCodes);
    }

    /** 兼容 DR-04 前形态的构造（无 recovery 块）：恢复参数按 timing 取缺省 */
    public DrillTemplate(String scenarioId, String name, String scenarioType,
                         String faultSource, String driver, String chaosFamily,
                         String target, List<String> symptomCodes,
                         String symptomDisplay, String impact, Timing timing,
                         ParamWhitelist params, Execution execution) {
        this(scenarioId, name, scenarioType, faultSource, driver, chaosFamily, target,
                symptomCodes, symptomDisplay, impact, timing, params, execution,
                Recovery.defaulted(timing));
    }
}
