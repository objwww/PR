package com.objwww.pr.control.alert.domain.budget;

import java.util.EnumMap;
import java.util.Map;

/**
 * Run 级硬预算（AM4 M4-08 纯逻辑部分）：step / tool / evidence / time 四类计数上限。
 *
 * <p>扣减语义：consume 先校验后记账，越界抛 BudgetExhaustedException 且不记账
 * （失败扣减不留半状态）。<b>原子性由调用方保证</b>——本件非线程安全，
 * 并发扣减（收尾事务内的行级锁）归 application/infrastructure，IT 另行覆盖。
 * time 预算的计量由调用方把已耗毫秒作为计数计入，本件只做上限判定，不读时钟。
 */
public final class RunBudget {

    /** 四类预算计数维度 */
    public enum Kind {
        STEP, TOOL_CALL, EVIDENCE, TIME_MILLIS
    }

    private final Map<Kind, Long> limits = new EnumMap<>(Kind.class);
    private final Map<Kind, Long> consumed = new EnumMap<>(Kind.class);

    public RunBudget(long maxSteps, long maxToolCalls, long maxEvidences, long maxTimeMillis) {
        put(limits, Kind.STEP, maxSteps);
        put(limits, Kind.TOOL_CALL, maxToolCalls);
        put(limits, Kind.EVIDENCE, maxEvidences);
        put(limits, Kind.TIME_MILLIS, maxTimeMillis);
        for (Kind kind : Kind.values()) {
            consumed.put(kind, 0L);
        }
    }

    /** 扣减指定维度 amount（≥1）；越界抛 BudgetExhaustedException 且不记账 */
    public void consume(Kind kind, long amount) {
        if (amount < 1) {
            throw new IllegalArgumentException("扣减量必须 ≥1，实际: " + amount);
        }
        long used = consumed.get(kind);
        long limit = limits.get(kind);
        if (used + amount > limit) {
            throw new BudgetExhaustedException(kind, limit, used, amount);
        }
        consumed.put(kind, used + amount);
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
