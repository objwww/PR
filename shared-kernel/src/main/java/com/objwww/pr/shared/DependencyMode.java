package com.objwww.pr.shared;

/**
 * outbox_dependency 依赖模式（v2.2 E3；与 V1 ck_dependency_mode 一致）。
 */
public enum DependencyMode {

    /** 前置须 CONFIRMED；前置 SUPERSEDED/FAILED_TERMINAL 时级联 SUPERSEDED 本命令 */
    REQUIRE_CONFIRMED,
    /** 前置到任意终态即放行 */
    REQUIRE_TERMINAL,
    /** 软依赖：前置 SUPERSEDED 不级联 */
    OPTIONAL
}
