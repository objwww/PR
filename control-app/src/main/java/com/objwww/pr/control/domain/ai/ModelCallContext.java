package com.objwww.pr.control.domain.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 模型调用上下文显式载体（§3.1/附录 C）：逐层透传，禁止 ThreadLocal/静态变量（AFT-25）。
 *
 * <p>构造于 ReviewStepExecutor，透传至 ReviewAgentLoop → ModelGateway。
 * 不含 invocationId（铸造权归 Gateway，v1.3 裁定）。
 *
 * <p>v1.4 实施偏差留痕：① leaseHeartbeat 用 {@link BooleanSupplier} 而非附录 C 的
 * LeaseHeartbeat 接口——该接口在 application 包，domain 反向依赖违层；BooleanSupplier
 * 语义等价（false = 租约已失效）。② 增补 prRevisionId——决策事件走 execution_event，
 * 其 pr_revision_id 列为 NOT NULL + FK，无此字段事件无法落库。
 */
public record ModelCallContext(
        UUID runId,
        UUID prRevisionId,
        UUID stepId,
        UUID attemptId,
        long leaseEpoch,
        Instant stepDeadline,
        BooleanSupplier leaseHeartbeat
) {
    public ModelCallContext {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(prRevisionId, "prRevisionId");
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(stepDeadline, "stepDeadline");
        Objects.requireNonNull(leaseHeartbeat, "leaseHeartbeat");
    }
}
