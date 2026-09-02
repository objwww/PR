package com.objwww.pr.control.domain.ai;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * per-route 三态熔断器（§4.5/I31）：CLOSED/OPEN/HALF_OPEN，迁移表唯一合法集。
 *
 * <p>只计"触网后失败"；ClientError 族/预算违约不累加也不清零；内存态（R-M3）。
 * 计时走注入的单调时钟（{@link System#nanoTime} 语义，EX-56/UT-61：系统时钟回拨/前跳
 * 不提前放行、不永久 OPEN）。
 *
 * <p>记录 OPEN 原因的 FaultScope（A16：熔断快败的 fallback 资格沿用 OPEN 原因域过矩阵）。
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long coolDownNanos;
    private final LongSupplier nanoTime;

    private final Object lock = new Object();
    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openedAtNanos;
    private FaultScope openedScope;

    public CircuitBreaker(int failureThreshold, long coolDownNanos) {
        this(failureThreshold, coolDownNanos, System::nanoTime);
    }

    public CircuitBreaker(int failureThreshold, long coolDownNanos, LongSupplier nanoTime) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold must be positive: " + failureThreshold);
        }
        if (coolDownNanos <= 0) {
            throw new IllegalArgumentException("coolDownNanos must be positive: " + coolDownNanos);
        }
        this.failureThreshold = failureThreshold;
        this.coolDownNanos = coolDownNanos;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * 原子领取调用许可（§4.5：检查状态与领取探针必须在同一原子块）：
     * CLOSED 直接放行；OPEN 冷却到期转 HALF_OPEN 并发放唯一探针；其余快败返回 null。
     */
    public BreakerPermit tryAcquire() {
        synchronized (lock) {
            if (state == State.CLOSED) {
                return new BreakerPermit(this);
            }
            if (state == State.OPEN) {
                if (nanoTime.getAsLong() - openedAtNanos >= coolDownNanos) {
                    state = State.HALF_OPEN;
                    return new BreakerPermit(this); // 恰好一发探针
                }
                return null; // 冷却未到期，快败
            }
            return null; // HALF_OPEN：探针已被领取，其余并发一律拒
        }
    }

    /** 成功：HALF_OPEN → CLOSED；CLOSED 清零连续失败计数。 */
    void onSuccess() {
        synchronized (lock) {
            if (state == State.HALF_OPEN) {
                state = State.CLOSED;
                openedScope = null;
            }
            consecutiveFailures = 0;
        }
    }

    /** 触网失败计数：CLOSED 达阈值 → OPEN；HALF_OPEN 探针失败 → OPEN 重计时。 */
    void onFailure(FaultScope scope) {
        synchronized (lock) {
            if (state == State.HALF_OPEN) {
                state = State.OPEN;
                openedAtNanos = nanoTime.getAsLong();
                openedScope = scope;
                return;
            }
            if (state == State.CLOSED) {
                consecutiveFailures++;
                if (consecutiveFailures >= failureThreshold) {
                    state = State.OPEN;
                    openedAtNanos = nanoTime.getAsLong();
                    openedScope = scope;
                }
            }
        }
    }

    /** 探针取消/中断或中性归还：不卡死 HALF_OPEN，转 OPEN 重计时（§4.5 探针悬挂防护）。 */
    void onCancelOrInconclusive() {
        synchronized (lock) {
            if (state == State.HALF_OPEN) {
                state = State.OPEN;
                openedAtNanos = nanoTime.getAsLong();
            }
        }
    }

    /** OPEN 原因的故障域（A16）；非 OPEN 态返回 null。 */
    public FaultScope openedScope() {
        synchronized (lock) {
            return state == State.OPEN ? openedScope : null;
        }
    }

    public State currentState() {
        synchronized (lock) {
            return state;
        }
    }

    public int consecutiveFailures() {
        synchronized (lock) {
            return consecutiveFailures;
        }
    }
}
