package com.objwww.pr.control.eval.domain.statemachine;

import com.objwww.pr.control.eval.domain.EvalRun;

import java.util.Objects;
import java.util.Set;

/**
 * EV-04 评测 run 生命周期状态机（取消受理与 L 模式恢复义务的单一裁决点；
 * docs/告警-评测中心与能力版本演进-审查及详细改造方案-v1.md EV-04 卡 / EU14）。
 *
 * <p>冻结语义：
 * <ul>
 *   <li><b>取消合法迁移</b>：仅 RUNNING 可受理取消（受理 = "取消中"，不表示已停）；
 *       SUCCEEDED/FAILED 终态上的取消一律拒绝（HTTP 409 面），零副作用；</li>
 *   <li><b>L 模式恢复义务</b>：mode=L 的取消不得直接终态——worker 停推进新案例后
 *       必须转 RECOVERING 并完成恢复核验（recovery_state：PENDING → RECOVERING →
 *       VERIFIED/FAILED），核验落账后才允许 FAILED 终态（"报告/实验结束不伪装现场
 *       恢复"，EU14）；E/B 模式无现场恢复义务（recovery_state 保持 NULL，读面
 *       NOT_APPLICABLE）；</li>
 *   <li><b>崩溃孤儿</b>：worker 失联的 CLAIMED 命令其 run 终态化 FAILED
 *       （terminal_reason=worker_lost）；L 模式孤儿 recovery_state 保持 PENDING
 *       （恢复未核验 = 如实现场未知，不冒充 VERIFIED）。</li>
 * </ul>
 * 纯裁决逻辑，零框架依赖（ControlArchitectureTest 域零依赖律）。
 */
public final class EvalRunLifecycle {

    /** 恢复核验分面词表（V81 ck_eval_run_recovery_state 同序） */
    public static final String RECOVERY_PENDING = "PENDING";
    public static final String RECOVERY_RECOVERING = "RECOVERING";
    public static final String RECOVERY_VERIFIED = "VERIFIED";
    public static final String RECOVERY_FAILED = "FAILED";

    /** 终态卡因词表（terminal_reason 前缀；详情投影直读） */
    public static final String REASON_CANCELLED = "cancelled_by_operator";
    public static final String REASON_WORKER_LOST = "worker_lost";

    private static final Set<String> MODES = Set.of("E", "B", "L");

    private EvalRunLifecycle() {
    }

    /** 取消受理裁决：仅 RUNNING 合法；终态拒绝 */
    public static boolean cancelLegal(EvalRun.EvalRunState state) {
        Objects.requireNonNull(state, "state 不得为 null");
        return state == EvalRun.EvalRunState.RUNNING;
    }

    /** mode 合法性（V80 ck_eval_run_mode 同词表） */
    public static boolean modeLegal(String mode) {
        return mode != null && MODES.contains(mode);
    }

    /** L 模式 = 真实靶场现场 → 取消/收场带恢复核验义务；E/B 无现场副作用 */
    public static boolean requiresRecovery(String mode) {
        return "L".equals(mode);
    }

    /** worker 失联孤儿的终态卡因（L 孤儿恢复仍未核验——reason 直说，不动 recovery_state） */
    public static String orphanTerminalReason(String mode) {
        return requiresRecovery(mode)
                ? REASON_WORKER_LOST + ";recovery_unverified"
                : REASON_WORKER_LOST;
    }
}
