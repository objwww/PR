package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.domain.service.ExecutionEventRepository;
import com.objwww.pr.shared.ExecutionEvent;
import com.objwww.pr.shared.ExecutionEventType;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * ExecutionEventRepository 的 Postgres 实现：只追加 + 按 Run 顺序读。
 * payload 落 jsonb；表由不可变 trigger 保护（I9），本类不提供 update/delete。
 * 与 PostgresSequenceAllocator 同样不加 Spring 注解，接线属后续任务。
 */
public class PostgresExecutionEventRepository implements ExecutionEventRepository {

    private static final String INSERT_SQL = """
            INSERT INTO execution_event (
                event_id, review_run_id, pr_revision_id, step_id, attempt_id,
                event_type, schema_version, causation_event_id, correlation_id,
                producer, payload, occurred_at
            ) VALUES (
                :eventId, :reviewRunId, :prRevisionId, :stepId, :attemptId,
                :eventType, :schemaVersion, :causationEventId, :correlationId,
                :producer, CAST(:payload AS jsonb), :occurredAt
            )
            """;

    private static final String SELECT_BY_RUN_SQL = """
            SELECT event_id, review_run_id, pr_revision_id, step_id, attempt_id,
                   event_type, schema_version, causation_event_id, correlation_id,
                   producer, payload, occurred_at
              FROM execution_event
             WHERE review_run_id = :reviewRunId
             ORDER BY position
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public PostgresExecutionEventRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public void append(ExecutionEvent event) {
        jdbc.sql(INSERT_SQL)
                .param("eventId", event.eventId())
                .param("reviewRunId", event.reviewRunId())
                .param("prRevisionId", event.prRevisionId())
                .param("stepId", event.stepId())
                .param("attemptId", event.attemptId())
                .param("eventType", event.eventType().name())
                .param("schemaVersion", event.schemaVersion())
                .param("causationEventId", event.causationEventId())
                .param("correlationId", event.correlationId())
                .param("producer", event.producer())
                .param("payload", toJson(event.payload()))
                .param("occurredAt", Timestamp.from(event.occurredAt()))
                .update();
    }

    @Override
    public List<ExecutionEvent> findByRunIdOrdered(UUID reviewRunId) {
        return jdbc.sql(SELECT_BY_RUN_SQL)
                .param("reviewRunId", Objects.requireNonNull(reviewRunId))
                .query((rs, rowNum) -> new ExecutionEvent(
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("review_run_id", UUID.class),
                        rs.getObject("pr_revision_id", UUID.class),
                        rs.getObject("step_id", UUID.class),
                        rs.getObject("attempt_id", UUID.class),
                        ExecutionEventType.valueOf(rs.getString("event_type")),
                        rs.getInt("schema_version"),
                        rs.getObject("causation_event_id", UUID.class),
                        rs.getObject("correlation_id", UUID.class),
                        rs.getString("producer"),
                        fromJson(rs.getString("payload")),
                        rs.getTimestamp("occurred_at").toInstant()))
                .list();
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("事件 payload 无法序列化为 jsonb", e);
        }
    }

    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("execution_event.payload 反序列化失败", e);
        }
    }
}
