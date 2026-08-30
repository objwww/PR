package com.objwww.pr.publisher.domain.service;

import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.publisher.domain.model.DependencyRow;
import com.objwww.pr.publisher.domain.model.SubjectCursor;

import java.util.List;
import java.util.Objects;

/**
 * T3-A 事务内加载的决策上下文（v2.2 E1：与状态推进同事务、同行锁）。
 */
public record T3AContext(
        ClaimedCommand command,
        List<DependencyRow> dependencies,
        SubjectCursor cursor) {

    public T3AContext {
        Objects.requireNonNull(command, "command");
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        Objects.requireNonNull(cursor, "cursor");
    }
}
