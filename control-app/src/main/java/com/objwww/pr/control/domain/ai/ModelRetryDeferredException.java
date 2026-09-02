package com.objwww.pr.control.domain.ai;

import java.time.Instant;

/**
 * 模型重试需长等待，Defer 到队列（§4.4/附录 C）。
 *
 * <p>Defer 传播链：Gateway → ReviewAgentLoop（不捕获）→ ReviewStepExecutor（不捕获）
 * → WorkItemWorker.classify() → StepOutcome.Failed(retryNotBefore) → ReviewOrchestrator
 * → workItem.retryLater(max(线性退避, notBefore)).
 */
public class ModelRetryDeferredException extends RuntimeException {
    private final Instant notBefore;

    public ModelRetryDeferredException(Instant notBefore, String message) {
        super(message);
        this.notBefore = notBefore;
    }

    public Instant notBefore() {
        return notBefore;
    }
}
