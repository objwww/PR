package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.agent.ContextSummary;
import com.objwww.pr.control.alert.domain.repository.ContextSummaryPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * rca_context_summary 的 Postgres 实现（R11，V92 不可变档）：uq(run, task,
 * source_snapshot_digest) 冲突 → 返回既有行（候选丢弃，CAS 提交语义）；当前指针
 * = created_at 最大的已提交行。
 */
public class PostgresContextSummary implements ContextSummaryPort {

    private static final TypeReference<List<String>> REFS =
            new TypeReference<>() {
            };

    private static final String COLUMNS = """
            id, run_id, task_id, schema_version, source_snapshot_digest,
            event_seq_from, event_seq_to, summary_prompt_digest, model,
            token_before, token_after, required_refs, omitted_refs,
            summary_text, summary_digest, validation_result, producer,
            config_epoch, created_at
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PostgresContextSummary(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public ContextSummary append(ContextSummary candidate) {
        List<UUID> existing = jdbc.sql("""
                select id from rca_context_summary
                where run_id = :runId and task_id = :taskId
                  and source_snapshot_digest = :source
                """)
                .param("runId", candidate.runId())
                .param("taskId", candidate.taskId())
                .param("source", candidate.sourceSnapshotDigest())
                .query((rs, i) -> rs.getObject("id", UUID.class))
                .list();
        if (!existing.isEmpty()) {
            return findById(existing.get(0));
        }
        jdbc.sql("""
                insert into rca_context_summary (id, run_id, task_id, schema_version,
                    source_snapshot_digest, event_seq_from, event_seq_to,
                    summary_prompt_digest, model, token_before, token_after,
                    required_refs, omitted_refs, summary_text, summary_digest,
                    validation_result, producer, config_epoch, created_at)
                values (:id, :runId, :taskId, :schemaVersion, :source, :seqFrom, :seqTo,
                    :promptDigest, :model, :tokenBefore, :tokenAfter,
                    cast(:requiredRefs as jsonb), cast(:omittedRefs as jsonb),
                    :summaryText, :summaryDigest, :validationResult, :producer,
                    :configEpoch, :createdAt)
                """)
                .param("id", candidate.id())
                .param("runId", candidate.runId())
                .param("taskId", candidate.taskId())
                .param("schemaVersion", candidate.schemaVersion())
                .param("source", candidate.sourceSnapshotDigest())
                .param("seqFrom", candidate.eventSeqFrom())
                .param("seqTo", candidate.eventSeqTo())
                .param("promptDigest", candidate.summaryPromptDigest())
                .param("model", candidate.model())
                .param("tokenBefore", candidate.tokenBefore())
                .param("tokenAfter", candidate.tokenAfter())
                .param("requiredRefs", jsonOf(candidate.requiredRefs()))
                .param("omittedRefs", jsonOf(candidate.omittedRefs()))
                .param("summaryText", candidate.summaryText())
                .param("summaryDigest", candidate.summaryDigest())
                .param("validationResult", candidate.validationResult())
                .param("producer", candidate.producer())
                .param("configEpoch", candidate.configEpoch())
                .param("createdAt", Timestamp.from(candidate.createdAt()))
                .update();
        return candidate;
    }

    @Override
    public Optional<ContextSummary> findBySource(UUID runId, UUID taskId,
            String sourceDigest) {
        List<ContextSummary> rows = jdbc.sql("select " + COLUMNS
                        + " from rca_context_summary where run_id = :runId and task_id = :taskId"
                        + " and source_snapshot_digest = :source")
                .param("runId", runId).param("taskId", taskId).param("source", sourceDigest)
                .query(this::mapRow).list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<ContextSummary> latestByTask(UUID runId, UUID taskId) {
        List<ContextSummary> rows = jdbc.sql("select " + COLUMNS
                        + " from rca_context_summary where run_id = :runId and task_id = :taskId"
                        + " order by created_at desc limit 1")
                .param("runId", runId).param("taskId", taskId)
                .query(this::mapRow).list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public long countByRun(UUID runId) {
        return jdbc.sql("select count(*) from rca_context_summary where run_id = :runId")
                .param("runId", runId)
                .query((rs, i) -> rs.getLong(1)).list().get(0);
    }

    @Override
    public long countByTask(UUID runId, UUID taskId) {
        return jdbc.sql("select count(*) from rca_context_summary"
                        + " where run_id = :runId and task_id = :taskId")
                .param("runId", runId).param("taskId", taskId)
                .query((rs, i) -> rs.getLong(1)).list().get(0);
    }

    private ContextSummary findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from rca_context_summary where id = :id")
                .param("id", id)
                .query(this::mapRow).list().get(0);
    }

    private ContextSummary mapRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Long configEpoch = (Long) rs.getObject("config_epoch");
        return new ContextSummary(rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("task_id", UUID.class),
                rs.getInt("schema_version"),
                rs.getString("source_snapshot_digest"),
                rs.getLong("event_seq_from"),
                rs.getLong("event_seq_to"),
                rs.getString("summary_prompt_digest"),
                rs.getString("model"),
                rs.getInt("token_before"),
                rs.getInt("token_after"),
                refsOf(rs.getString("required_refs")),
                refsOf(rs.getString("omitted_refs")),
                rs.getString("summary_text"),
                rs.getString("summary_digest"),
                rs.getString("validation_result"),
                rs.getString("producer"),
                configEpoch, createdAt.toInstant());
    }

    private String jsonOf(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("refs jsonb 序列化失败: " + e.getMessage(), e);
        }
    }

    private List<String> refsOf(String json) {
        try {
            return mapper.readValue(json, REFS);
        } catch (Exception e) {
            throw new IllegalStateException("refs jsonb 解析失败: " + e.getMessage(), e);
        }
    }
}
