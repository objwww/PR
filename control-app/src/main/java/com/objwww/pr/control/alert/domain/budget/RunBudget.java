package com.objwww.pr.control.alert.domain.budget;

import java.util.EnumMap;
import java.util.Map;

/**
 * Run 级硬预算（AM4 M4-08 纯逻辑部分）：STEP / TOOL_CALL / EVIDENCE / SUBTASK 四类计数上限
 * + TIME 固定 deadline 语义。
 *
 * <p>扣减语义：consume 先校验后记账，越界抛 BudgetExhaustedException 且不记账
 * （失败扣减不留半状态）；used + amount 用 Math.addExact，long 溢出视为越界
 * （溢出即天文数字请求，必超上限）。TIME 不做 elapsed 多次累加——调用方传入固定
 * deadline（epoch millis），checkDeadline(now) 判定超期（now &gt; deadline 即耗尽）。
 *
 * <p><b>终局正确性声明</b>：本类是规则计算器，非线程安全；硬预算在并发下的终局正确性由
 * DB 原子"预留→提交/释放"保证（收尾事务行级锁，后续批次落 infrastructure/IT 覆盖）。
 */
public final class RunBudget {

    /** 计数维度（TIME 不在其中——TIME 是固定 deadline，见 checkDeadline） */
    public enum Kind {
        STEP, TOOL_CALL, EVIDENCE, SUBTASK
    }

    private final Map<Kind, Long> limits = new EnumMap<>(Kind.class);
    private final Map<Kind, Long> consumed = new EnumMap<>(Kind.class);
    private final long deadlineEpochMillis;

    public RunBudget(long maxSteps, long maxToolCalls, long maxEvidences, long maxSubtasks,
            long deadlineEpochMillis) {
        put(limits, Kind.STEP, maxSteps);
        put(limits, Kind.TOOL_CALL, maxToolCalls);
        put(limits, Kind.EVIDENCE, maxEvidences);
        put(limits, Kind.SUBTASK, maxSubtasks);
        this.deadlineEpochMillis = deadlineEpochMillis;
        for (Kind kind : Kind.values()) {
            consumed.put(kind, 0L);
        }
    }

    /** 扣减指定维度 amount（≥1）；越界（含 long 溢出）抛 BudgetExhaustedException 且不记账 */
    public void consume(Kind kind, long amount) {
        if (amount < 1) {
            throw new IllegalArgumentException("扣减量必须 ≥1，实际: " + amount);
        }
        long used = consumed.get(kind);
        long limit = limits.get(kind);
        long after;
        try {
            after = Math.addExact(used, amount);
        } catch (ArithmeticException overflow) {
            throw new BudgetExhaustedException(kind, limit, used, amount);
        }
        if (after > limit) {
            throw new BudgetExhaustedException(kind, limit, used, amount);
        }
        consumed.put(kind, after);
    }

    /** TIME 维度：now 超过固定 deadline 即耗尽（now == deadline 仍视为在窗口内） */
    public void checkDeadline(long nowEpochMillis) {
        if (nowEpochMillis > deadlineEpochMillis) {
            throw BudgetExhaustedException.deadlineExceeded(deadlineEpochMillis, nowEpochMillis);
        }
    }

    public long deadlineEpochMillis() {
        return deadlineEpochMillis;
    }

    public long consumed(Kind kind) {
        return consumed.get(kind);
    }

    public long remaining(Kind kind) {
        return limits.get(kind) - consumed.get(kind);
    }

    public long limit(Kind kind) {
        return limits.get(kind);
    }

    private static void put(Map<Kind, Long> map, Kind kind, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("预算上限不得为负: " + kind + "=" + value);
        }
        map.put(kind, value);
    }
}
