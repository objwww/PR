package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.drill.domain.repository.ChangeEventLedger;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;

/**
 * BA-185 {@link ChangeEventLedger} Postgres 实现：V40 change_event append-only
 * 写入面——control_app 角色持 select+insert（V40 授权面，零新授权）；幂等锚
 * (source, deploy_id) 唯一约束下 {@code on conflict do nothing}（重放零重复
 * 生效事件，BA-75 纪律：无目标式）。写失败向调用方抛（生效而无证据 = 判败，
 * 不静默）。
 */
public class PostgresChangeEventLedger implements ChangeEventLedger {

    private static final String INSERT_DEPLOY_SQL = """
            INSERT INTO change_event (
                id, deploy_id, source, action, service, environment,
                image_digest, config_digest, commit_sha, actor,
                started_at, effective_at, rollback_of, status
            ) VALUES (
                :id, :deployId, 'deployment', 'DEPLOY', :service, :environment,
                NULL, :configDigest, NULL, :actor,
                :effectiveAt, :effectiveAt, NULL, 'SUCCEEDED'
            )
            ON CONFLICT DO NOTHING
            """;

    private static final String INSERT_ROLLBACK_SQL = """
            INSERT INTO change_event (
                id, deploy_id, source, action, service, environment,
                image_digest, config_digest, commit_sha, actor,
                started_at, effective_at, rollback_of, status
            ) VALUES (
                :id, :deployId, 'deployment', 'ROLLBACK', :service, :environment,
                NULL, :configDigest, NULL, :actor,
                :effectiveAt, :effectiveAt, :rollbackOf, 'SUCCEEDED'
            )
            ON CONFLICT DO NOTHING
            """;

    private final JdbcClient jdbc;

    public PostgresChangeEventLedger(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不得为 null");
    }

    @Override
    public void recordDeploy(DeployFact fact) {
        Objects.requireNonNull(fact, "fact 不得为 null");
        jdbc.sql(INSERT_DEPLOY_SQL)
                .param("id", UUID.randomUUID())
                .param("deployId", fact.deployId())
                .param("service", fact.service())
                .param("environment", fact.environment())
                .param("configDigest", fact.configDigest())
                .param("actor", fact.actor())
                .param("effectiveAt", Timestamp.from(fact.effectiveAt()))
                .update();
    }

    @Override
    public void recordRollback(RollbackFact fact) {
        Objects.requireNonNull(fact, "fact 不得为 null");
        jdbc.sql(INSERT_ROLLBACK_SQL)
                .param("id", UUID.randomUUID())
                .param("deployId", fact.deployId())
                .param("service", fact.service())
                .param("environment", fact.environment())
                .param("configDigest", fact.configDigest())
                .param("rollbackOf", fact.rollbackOf())
                .param("actor", fact.actor())
                .param("effectiveAt", Timestamp.from(fact.effectiveAt()))
                .update();
    }
}
