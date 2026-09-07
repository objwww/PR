package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.OperatorCommand;
import com.objwww.pr.control.alert.domain.repository.OperatorCommandRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * V27 operator_command 的 Postgres 实现（M5-14）。insert 撞
 * uq(run_id, command_type, idempotency_key) 抛 DuplicateKeyException（并发同键
 * 恰一行，败者读胜者行）；updateState 带 state='PERSISTED' 谓词——终态行二次
 * 推进 0 行（幂等二次生效零容忍的 DB 面）。
 */
public class PostgresOperatorCommandRepository implements OperatorCommandRepository {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> PAYLOAD =
            new TypeReference<>() {
            };

    private final JdbcClient jdbc;

    public PostgresOperatorCommandRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insert(OperatorCommand command) {
        jdbc.sql("""
                INSERT INTO operator_command (
                    id, run_id, command_type, idempotency_key, expected_revision,
                    payload, state, actor, created_at
                ) VALUES (
                    :id, :runId, :type, :key, :expectedRevision,
                    CAST(:payload AS jsonb), :state, :actor, :createdAt
                )
                """)
                .param("id", command.id())
                .param("runId", command.runId())
                .param("type", command.type().name())
                .param("key", command.idempotencyKey())
                .param("expectedRevision", command.expectedRevision())
                .param("payload", jsonOf(command.payload()))
                .param("state", command.state().name())
                .param("actor", command.actor())
                .param("createdAt", Timestamp.from(command.createdAt()))
                .update();
    }

    @Override
    public Optional<OperatorCommand> find(UUID runId, OperatorCommand.Type type,
                                          String idempotencyKey) {
        return jdbc.sql("""
                        SELECT * FROM operator_command
                         WHERE run_id = :runId AND command_type = :type
                           AND idempotency_key = :key
                        """)
                .param("runId", runId)
                .param("type", type.name())
                .param("key", idempotencyKey)
                .query(this::mapRow)
                .optional();
    }

    @Override
    public boolean updateState(UUID id, OperatorCommand.State state, Instant appliedAt) {
        return jdbc.sql("""
                UPDATE operator_command SET state = :state, applied_at = :appliedAt
                 WHERE id = :id AND state = 'PERSISTED'
                """)
                .param("state", state.name())
                .param("appliedAt", appliedAt == null ? null : Timestamp.from(appliedAt),
                        Types.TIMESTAMP)
                .param("id", id)
                .update() > 0;
    }

    private OperatorCommand mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp appliedAt = rs.getTimestamp("applied_at");
        return new OperatorCommand(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                OperatorCommand.Type.valueOf(rs.getString("command_type")),
                rs.getString("idempotency_key"),
                rs.getLong("expected_revision"),
                payloadOf(rs.getString("payload")),
                OperatorCommand.State.valueOf(rs.getString("state")),
                rs.getString("actor"),
                createdAt.toInstant(),
                appliedAt == null ? null : appliedAt.toInstant());
    }

    private static String jsonOf(Map<String, Object> payload) {
        try {
            return JSON.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            throw new IllegalStateException("命令 payload 序列化失败", e);
        }
    }

    private static Map<String, Object> payloadOf(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, PAYLOAD);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
