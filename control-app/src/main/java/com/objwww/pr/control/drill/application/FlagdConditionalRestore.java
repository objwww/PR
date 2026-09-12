package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.FlagdState;

/**
 * DR-05 flagd 条件恢复纯逻辑（方案 docs/告警-前端逐页体验改造与后期优化方案.md
 * §7.4「只在当前值仍属于本次写入时条件恢复，发现他人修改则冲突待处理。不能未经
 * 比较直接覆盖用户后来修改的 flag」）——driver 正常解除与 sweeper 截止清扫共用的
 * 同一份判定：
 * <ul>
 *   <li>RESTORED：当前值仍等于本次写入值且代际未被他者推进 → 写回恢复目标；</li>
 *   <li>CONFLICT：当前值/代际任一不符（他者已改写，含同值重写）→ 不覆盖、不宣称
 *       恢复成功；</li>
 *   <li>UNKNOWN：读/写传输失败或读空 → 不盲写，留可恢复面待重试；</li>
 *   <li>NOT_APPLIED：写回后服务端回执与目标不符 → 不宣称恢复成功。</li>
 * </ul>
 */
public final class FlagdConditionalRestore {

    public enum Outcome {RESTORED, CONFLICT, UNKNOWN, NOT_APPLIED}

    public record Result(Outcome outcome, String detail) {
    }

    private FlagdConditionalRestore() {
    }

    /**
     * 条件恢复一次尝试：读当前 → 比对所有权（值 + 代际）→ 属本次写入才写回。
     *
     * @param expectedVariant    本次写入值（台账 appliedVariant；无台账路径 = injection.variant）
     * @param expectedGeneration 本次写入的代际令牌（null = 服务端不给代际，退化为纯值比对）
     * @param restoreTarget      恢复目标（实际原值优先，模板 baseline 兜底）
     */
    public static Result attempt(FlagdAdminPort port, String flag,
                                 String expectedVariant, String expectedGeneration,
                                 String restoreTarget) {
        FlagdState current;
        try {
            current = port.read(flag);
        } catch (RuntimeException e) {
            return new Result(Outcome.UNKNOWN, "read_failed:" + e.getMessage());
        }
        if (current == null || current.variant() == null) {
            return new Result(Outcome.UNKNOWN, "read_empty: 服务端未返回当前值");
        }
        boolean valueOwned = expectedVariant.equals(current.variant());
        boolean generationOwned = expectedGeneration == null || current.generation() == null
                || expectedGeneration.equals(current.generation());
        if (!valueOwned || !generationOwned) {
            return new Result(Outcome.CONFLICT, "current=" + current.variant()
                    + ",generation=" + current.generation()
                    + "（非本次写入面：他者已改写——不覆盖、不宣称恢复成功）");
        }
        String applied;
        try {
            applied = port.write(flag, restoreTarget);
        } catch (RuntimeException e) {
            return new Result(Outcome.UNKNOWN, "write_failed:" + e.getMessage());
        }
        if (!restoreTarget.equals(applied)) {
            return new Result(Outcome.NOT_APPLIED, applied);
        }
        return new Result(Outcome.RESTORED, "restored=" + applied);
    }
}
