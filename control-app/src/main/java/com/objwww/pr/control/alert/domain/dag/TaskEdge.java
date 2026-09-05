package com.objwww.pr.control.alert.domain.dag;

import java.util.Objects;

/**
 * DAG 依赖边（AM4 §3.1）：fromTaskId 是 toTaskId 的前置（from 先终态，to 才可推进）。
 * 纯值对象，不做推进判断——推进规则见 DagPromoter。
 */
public record TaskEdge(String fromTaskId, String toTaskId, DependencyType dependencyType) {

    public TaskEdge {
        Objects.requireNonNull(fromTaskId, "fromTaskId");
        Objects.requireNonNull(toTaskId, "toTaskId");
        Objects.requireNonNull(dependencyType, "dependencyType");
    }
}
