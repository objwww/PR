package com.objwww.pr.publisher.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.publisher.domain.port.ExecutionEventAppender;
import com.objwww.pr.shared.ExecutionEvent;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.Objects;

/**
 * execution_event 最小 PG append 实现（Publisher 只 INSERT，I9）。
 * 刻意不加 Spring 注解；与调用方同一 JdbcClient 时自动参与其事务
 * （TransactionTemplate 绑定的连接经 DataSourceUtils 共享）。
 */
public class PostgresExecutionEventAppender implements ExecutionEventAppender {

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

    public PostgresExecutionEventAppender(JdbcClient jdbc, ObjectMapper objectMapper) {
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
                .param("payload", toJson(event))
                .param("occurredAt", Timestamp.from(event.occurredAt()))
                .update();
    }

    private String toJson(ExecutionEvent event) {
        try {
            return objectMapper.writeValueAsString(event.payload());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("事件 payload 无法序列化为 jsonb", e);
        }
    }
}
