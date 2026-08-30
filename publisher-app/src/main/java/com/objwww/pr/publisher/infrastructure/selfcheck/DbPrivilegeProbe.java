package com.objwww.pr.publisher.infrastructure.selfcheck;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;

/**
 * DB 权限元数据探针（T15）：{@code SELECT has_table_privilege(current_user, :table, :privilege)}。
 * 只读元数据断言——不做真实写入试探（§6.5）。
 */
@FunctionalInterface
public interface DbPrivilegeProbe {

    boolean hasTablePrivilege(String table, String privilege);

    static DbPrivilegeProbe postgres(JdbcClient jdbc) {
        Objects.requireNonNull(jdbc, "jdbc");
        return (table, privilege) -> jdbc.sql(
                        "SELECT has_table_privilege(current_user, :table, :privilege)")
                .param("table", table)
                .param("privilege", privilege)
                .query(Boolean.class)
                .single();
    }
}
