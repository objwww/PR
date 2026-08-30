package com.objwww.pr.publisher.domain.model;

import com.objwww.pr.shared.DependencyMode;
import com.objwww.pr.shared.OperationId;
import com.objwww.pr.shared.OutboxState;

import java.util.Objects;

/**
 * 一条依赖边的前置侧视图（outbox_dependency JOIN 前置命令）。
 */
public record DependencyRow(
        OperationId dependsOnOperationId,
        OutboxState prerequisiteState,
        DependencyMode mode) {

    public DependencyRow {
        Objects.requireNonNull(dependsOnOperationId, "dependsOnOperationId");
        Objects.requireNonNull(prerequisiteState, "prerequisiteState");
        Objects.requireNonNull(mode, "mode");
    }
}
