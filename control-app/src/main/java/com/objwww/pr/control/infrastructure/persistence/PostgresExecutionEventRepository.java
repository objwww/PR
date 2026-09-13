package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.domain.service.ExecutionEventRepository;
import com.objwww.pr.shared.ExecutionEvent;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.Map;
import java.util.Objects;

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

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("事件 payload 无法序列化为 jsonb", e);
        }
    }
}
