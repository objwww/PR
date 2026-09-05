package com.objwww.pr.control.alert.domain.dag;

/**
 * DAG 依赖边类型（AM4 §3.1 model/TaskEdge）：
 * REQUIRED = 前置必须 SUCCEEDED 才推进；OPTIONAL = 前置到达任一终态即放行。
 */
public enum DependencyType {
    REQUIRED, OPTIONAL
}
