package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.agent.DelegationReceipt;
import com.objwww.pr.control.alert.domain.repository.DelegationReceiptRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * rca_delegation_receipt 的 Postgres 实现（MC21~23，V96）。append-only 台账：
 * insert 唯一键冲突显式抛（message_id 幂等面的竞态短路依据）。jsonb 序列化
 * 走 ObjectMapper（PostgresContextSummary 同律）。
 */
public class PostgresDelegationReceiptRepository implements DelegationReceiptRepository {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PostgresDelegationReceiptRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void insert(DelegationReceipt r) {
        jdbc.sql("""
                INSERT INTO rca_delegation_receipt (
                    id, message_id, run_id, primary_task_id, child_task_id,
                    round_id, gap_id, role_id, child_status, admission,
                    findings, support_refs, counter_refs, missing_information,
                    payload_digest, payload_bytes, received_at
                ) VALUES (
                    :id, :messageId, :runId, :primaryTaskId, :childTaskId,
                    :roundId, :gapId, :roleId, :childStatus, :admission,
                    CAST(:findings AS jsonb), CAST(:supportRefs AS jsonb),
                    CAST(:counterRefs AS jsonb), CAST(:missingInformation AS jsonb),
                    :payloadDigest, :payloadBytes, :receivedAt
                )
                """)
                .param("id", r.id())
                .param("messageId", r.messageId())
                .param("runId", r.runId())
                .param("primaryTaskId", r.primaryTaskId())
                .param("childTaskId", r.childTaskId())
                .param("roundId", r.roundId())
                .param("gapId", r.gapId())
                .param("roleId", r.roleId())
                .param("childStatus", r.childStatus().name())
                .param("admission", r.admission().name())
                .param("findings", toJson(r.findings()))
                .param("supportRefs", toJson(r.supportRefs()))
                .param("counterRefs", toJson(r.counterRefs()))
                .param("missingInformation", toJson(r.missingInformation()))
                .param("payloadDigest", r.payloadDigest())
                .param("payloadBytes", r.payloadBytes())
                .param("receivedAt", Timestamp.from(r.receivedAt()))
                .update();
    }

    @Override
    public Optional<DelegationReceipt> findByMessageId(UUID messageId) {
        List<DelegationReceipt> rows = jdbc.sql("""
                SELECT * FROM rca_delegation_receipt WHERE message_id = :messageId
                """)
                .param("messageId", messageId)
                .query(this::mapRow)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<DelegationReceipt> findAcceptedByRunAndRound(UUID runId,
            UUID primaryTaskId, int roundId) {
        return jdbc.sql("""
                SELECT * FROM rca_delegation_receipt
                 WHERE run_id = :runId AND primary_task_id = :taskId
                   AND round_id = :roundId AND admission = 'ACCEPTED'
                 ORDER BY received_at, id
                """)
                .param("runId", runId)
                .param("taskId", primaryTaskId)
                .param("roundId", roundId)
                .query(this::mapRow)
                .list();
    }

    @Override
    public List<DelegationReceipt> findByChildTaskId(UUID childTaskId) {
        return jdbc.sql("""
                SELECT * FROM rca_delegation_receipt
                 WHERE child_task_id = :childTaskId
                 ORDER BY received_at, id
                """)
                .param("childTaskId", childTaskId)
                .query(this::mapRow)
                .list();
    }

    private String toJson(List<String> items) {
        try {
            return mapper.writeValueAsString(items);
        } catch (Exception e) {
            throw new IllegalStateException("回执 jsonb 序列化失败: " + e.getMessage(), e);
        }
    }

    private List<String> fromJson(String json) {
        try {
            return mapper.readValue(json, STRINGS);
        } catch (Exception e) {
            throw new IllegalStateException("回执 jsonb 解析失败: " + e.getMessage(), e);
        }
    }

    private DelegationReceipt mapRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new DelegationReceipt(
                rs.getObject("id", UUID.class),
                rs.getObject("message_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("primary_task_id", UUID.class),
                rs.getObject("child_task_id", UUID.class),
                rs.getInt("round_id"),
                rs.getString("gap_id"),
                rs.getString("role_id"),
                DelegationReceipt.ChildStatus.valueOf(rs.getString("child_status")),
                DelegationReceipt.Admission.valueOf(rs.getString("admission")),
                fromJson(rs.getString("findings")),
                fromJson(rs.getString("support_refs")),
                fromJson(rs.getString("counter_refs")),
                fromJson(rs.getString("missing_information")),
                rs.getString("payload_digest"),
                rs.getInt("payload_bytes"),
                rs.getTimestamp("received_at").toInstant());
    }
}
