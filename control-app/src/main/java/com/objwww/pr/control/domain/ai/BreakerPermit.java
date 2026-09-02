package com.objwww.pr.control.domain.ai;

import java.util.Objects;

/**
 * HALF_OPEN 探针/普通调用许可（§4.5/I31）：{@link CircuitBreaker#tryAcquire} 原子领取，
 * 用毕必须归还——三态语义：
 *
 * <ul>
 *   <li>{@link #onSuccess()}：调用成功（HALF_OPEN → CLOSED，计数清零）；</li>
 *   <li>{@link #onFailure(FaultScope)}：触网后失败，计入熔断；</li>
 *   <li>{@link #close()}：中性归还——未触网/ClientError 族/中断取消（不累加也不清零；
 *       HALF_OPEN 下探针无结论，转 OPEN 重计时，防 permit 泄漏卡态）。
 * </ul>
 *
 * <p>try-with-resources 包住触网区即满足"permit 归还放 finally"（F-9 实证坑）。
 */
public final class BreakerPermit implements AutoCloseable {

    private final CircuitBreaker breaker;
    private boolean released = false;

    BreakerPermit(CircuitBreaker breaker) {
        this.breaker = Objects.requireNonNull(breaker);
    }

    public void onSuccess() {
        if (!released) {
            breaker.onSuccess();
            released = true;
        }
    }

    public void onFailure(FaultScope scope) {
        if (!released) {
            breaker.onFailure(scope);
            released = true;
        }
    }

    @Override
    public void close() {
        if (!released) {
            breaker.onCancelOrInconclusive();
            released = true;
        }
    }
}
