package com.objwww.pr.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * 操作幂等键（V1：outbox_command.operation_id / run_step.operation_id，uuid）。
 * 唯一约束在 DB 层兜底（v2.2 §6.4，禁止 check-then-act）。
 */
public record OperationId(UUID value) {

    public OperationId {
        Objects.requireNonNull(value, "operationId");
    }

    public static OperationId random() {
        return new OperationId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
