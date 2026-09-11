package com.objwww.pr.control.drill.domain.statemachine;

import com.objwww.pr.control.drill.domain.model.DrillJob;

import java.util.Map;
import java.util.Set;

/**
 * DR-02 演练状态机（方案 §7.4 逐条落码，纯函数零依赖）：
 * <pre>
 *   QUEUED   → PRECHECK | CANCELLED            （注入前取消可直接 CANCELLED）
 *   PRECHECK → INJECTING | FAILED | CANCELLED  （预检失败零副作用可直接 FAILED）
 *   INJECTING → OBSERVING | FAILED | RECOVERING
 *              （FAILED 仅"注入确定未执行"合法——零副作用；注入可能发生即必走恢复路径）
 *   OBSERVING → RECOVERING                     （停止/持续期满都先进恢复路径）
 *   RECOVERING → VERIFYING | RECOVERY_FAILED   （恢复超时/残留 = RECOVERY_FAILED 保留占位）
 *   RECOVERY_FAILED → RECOVERING               （处理入口：人工核验后重试恢复）
 *   VERIFYING → CLOSED | RECOVERY_FAILED       （CLOSED 唯一来源 = VERIFYING）
 * </pre>
 * CLOSED ≠ 成功：outcome（PASS/FAIL/INCONCLUSIVE）另存，仅 CLOSED 落值
 * （DrillJob 构造器与 V86 CHECK 双向钉死）。
 */
public final class DrillLifecycle {

    private DrillLifecycle() {
    }

    private static final Map<DrillJob.State, Set<DrillJob.State>> LEGAL = Map.of(
            DrillJob.State.QUEUED, Set.of(DrillJob.State.PRECHECK, DrillJob.State.CANCELLED),
            DrillJob.State.PRECHECK, Set.of(DrillJob.State.INJECTING, DrillJob.State.FAILED,
                    DrillJob.State.CANCELLED),
            DrillJob.State.INJECTING, Set.of(DrillJob.State.OBSERVING, DrillJob.State.FAILED,
                    DrillJob.State.RECOVERING),
            DrillJob.State.OBSERVING, Set.of(DrillJob.State.RECOVERING),
            DrillJob.State.RECOVERING, Set.of(DrillJob.State.VERIFYING,
                    DrillJob.State.RECOVERY_FAILED),
            DrillJob.State.RECOVERY_FAILED, Set.of(DrillJob.State.RECOVERING),
            DrillJob.State.VERIFYING, Set.of(DrillJob.State.CLOSED,
                    DrillJob.State.RECOVERY_FAILED));

    public static boolean transitionLegal(DrillJob.State from, DrillJob.State to) {
        return LEGAL.getOrDefault(from, Set.of()).contains(to);
    }

    /** 停止受理后的目标路径：注入前 = CANCELLED；注入可能发生起 = 恢复路径（RECOVERING） */
    public static DrillJob.State stopPath(DrillJob.State current) {
        return switch (current) {
            case QUEUED, PRECHECK -> DrillJob.State.CANCELLED;
            case INJECTING, OBSERVING, RECOVERING, VERIFYING -> DrillJob.State.RECOVERING;
            default -> throw new IllegalArgumentException("状态 " + current + " 不可停止");
        };
    }

    /**
     * 停止是否可受理：活动中（QUEUED~VERIFYING）合法；终态与 RECOVERY_FAILED 非法
     * （RECOVERY_FAILED 已在恢复异常占位，需要的是处理恢复而非停止）。
     */
    public static boolean stopLegal(DrillJob.State current) {
        return switch (current) {
            case QUEUED, PRECHECK, INJECTING, OBSERVING, RECOVERING, VERIFYING -> true;
            default -> false;
        };
    }
}
