package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.application.mutation.UnlockScopeStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Objects;
import java.util.Optional;

/**
 * V121 mutation_unlock_registry 的 Postgres 实现（PD-D1）。
 */
public class PostgresUnlockScopeStore implements UnlockScopeStore {

    private final JdbcClient jdbc;
    private final TransactionOperations tx;

    public PostgresUnlockScopeStore(JdbcClient jdbc, TransactionOperations tx) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
    }

    @Override
    public Optional<UnlockScope> findByTool(String toolName) {
        return tx.execute(status -> jdbc.sql("""
                        select tool_name, resource_uid, canonical_env, enabled
                          from mutation_unlock_registry where tool_name = :tool
                        """)
                .param("tool", toolName)
                .query((rs, n) -> new UnlockScope(rs.getString("tool_name"),
                        rs.getString("resource_uid"), rs.getString("canonical_env"),
                        rs.getBoolean("enabled")))
                .optional());
    }

    @Override
    public Optional<String> envOfResource(String resourceUid) {
        return tx.execute(status -> jdbc.sql(
                        "select canonical_env from resource_inventory where resource_uid = :uid")
                .param("uid", resourceUid)
                .query(String.class)
                .optional());
    }
}
