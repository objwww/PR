package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.DrillEvent;
import com.objwww.pr.control.drill.domain.model.DrillJob;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DR-02 八阶段时间线推导（§7.2 详情页契约；纯函数）：
 * 受理 → 预检 → 注入 → 产生测试流量 → 等待症状 → Agent 调查 → 停止 → 核验恢复。
 *
 * <p>诚实面：
 * <ul>
 *   <li>enteredAt 只取真实事件（相位迁移 / 停止请求），不从倒计时推断；无独立事件
 *       的子阶段（产生测试流量/Agent 调查）enteredAt 如实 null；</li>
 *   <li>状态取值 DONE/ACTIVE/FAILED/PENDING/SKIPPED：失败点所在阶段 = FAILED，
 *       其后阶段 = SKIPPED（不渲染"未开始"伪装还有希望）；注入前取消 = 取消点之后
 *       SKIPPED（零注入无需恢复核验，如实而非"跳过恢复"）；</li>
 *   <li>CLOSED ≠ 成功：核验恢复 DONE 只表示恢复核验完成，outcome 由详情顶层另存。</li>
 * </ul>
 */
public final class DrillTimeline {

    private DrillTimeline() {
    }

    public enum Status {DONE, ACTIVE, FAILED, CANCELLED, PENDING, SKIPPED}

    public record Stage(String key, String name, String phase, Status status,
                        Instant enteredAt) {
    }

    /** 八阶段键序（与 §7.2 页面骨架一致） */
    private static final String[] KEYS = {
            "ACCEPTED", "PRECHECK", "INJECTION", "TRAFFIC",
            "SYMPTOM_WAIT", "AGENT_INVESTIGATION", "STOP", "VERIFY_RECOVERY"};
    private static final String[] NAMES = {
            "受理", "预检", "注入", "产生测试流量", "等待症状", "Agent 调查",
            "停止", "核验恢复"};
    private static final String[] PHASES = {
            "QUEUED", "PRECHECK", "INJECTING", "INJECTING",
            "OBSERVING", "OBSERVING", "RECOVERING", "VERIFYING→CLOSED"};

    /** 相位 → 主阶段索引（TRAFFIC 归 INJECTING 主阶段 INJECTION；子阶段另行推导） */
    private static int stageIndexOf(DrillJob.State state) {
        return switch (state) {
            case QUEUED -> 0;
            case PRECHECK -> 1;
            case INJECTING -> 2;
            case OBSERVING -> 4;
            case RECOVERING -> 6;
            case VERIFYING, CLOSED -> 7;
            case CANCELLED, FAILED, RECOVERY_FAILED -> -1; // 由失败点推导
        };
    }

    public static List<Stage> of(DrillJob job, List<DrillEvent> events) {
        // 各相位进入时刻 = PHASE_TRANSITION 事件 to_state（首次）；停止时刻 = STOP_REQUESTED
        Map<String, Instant> entered = new HashMap<>();
        String terminalFrom = null;
        DrillJob.State terminalTo = null;
        for (DrillEvent e : events) {
            if (e.eventType() == DrillEvent.EventType.PHASE_TRANSITION) {
                entered.putIfAbsent(e.toState(), e.createdAt());
                if (DrillJob.State.valueOf(e.toState()).isTerminal()
                        || DrillJob.State.valueOf(e.toState())
                        == DrillJob.State.RECOVERY_FAILED) {
                    terminalFrom = e.fromState();
                    terminalTo = DrillJob.State.valueOf(e.toState());
                }
            }
        }
        Instant stopAt = job.stopRequestedAt();

        List<Stage> stages = new ArrayList<>();
        // 失败/取消点：终态迁移的 from_state；无终态迁移（进行中）= 当前相位
        int failureIdx = terminalFrom == null ? -1
                : stageIndexOf(DrillJob.State.valueOf(terminalFrom));
        boolean cancelled = terminalTo == DrillJob.State.CANCELLED;
        boolean preInjectionCancel = cancelled
                && (failureIdx <= 1);

        for (int i = 0; i < KEYS.length; i++) {
            Status status;
            Instant enteredAt = switch (i) {
                case 0 -> job.createdAt();
                case 6 -> stopAt;
                case 7 -> entered.get(DrillJob.State.VERIFYING.name());
                default -> entered.get(PHASES[i]);
            };
            if (i == 0) {
                status = Status.DONE; // 受理 = 作业行存在本身
            } else if (job.state() == DrillJob.State.CLOSED) {
                status = Status.DONE;
            } else if (preInjectionCancel && i > failureIdx) {
                status = Status.SKIPPED; // 零注入取消：无需恢复核验
            } else if (failureIdx >= 0 && i > failureIdx) {
                status = Status.SKIPPED;
            } else if (failureIdx >= 0 && i == failureIdx) {
                status = cancelled ? Status.CANCELLED : Status.FAILED;
            } else if (failureIdx >= 0) {
                status = Status.DONE; // 失败点之前（RECOVERY_FAILED 同此三分支：失败点
                                      // FAILED、其后 SKIPPED——恢复异常占位如实呈现）
            } else {
                int current = stageIndexOf(job.state());
                if (i < current) {
                    status = Status.DONE;
                } else if (i == current) {
                    status = Status.ACTIVE;
                } else if (i == 3 && current == 2) {
                    // 产生测试流量是 INJECTING 内子阶段：主阶段 ACTIVE 时如实 PENDING
                    status = Status.PENDING;
                } else {
                    status = Status.PENDING;
                }
            }
            // 子阶段到达面修正：OBSERVING 已进入 → 流量/症状等待 DONE；RECOVERING
            // 已进入 → Agent 调查面 DONE（调查状态独立关联，不由本阶段冒充）
            if (terminalFrom == null && !job.state().isTerminal()
                    && job.state() != DrillJob.State.RECOVERY_FAILED) {
                int current = stageIndexOf(job.state());
                if (i == 3 && current >= 4) {
                    status = Status.DONE;
                }
                if (i == 5 && current >= 6) {
                    status = Status.DONE;
                }
            }
            stages.add(new Stage(KEYS[i], NAMES[i], PHASES[i], status, enteredAt));
        }
        return List.copyOf(stages);
    }
}
