package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.model.GoldenCandidate;
import com.objwww.pr.control.eval.domain.model.GoldenCandidateState;
import com.objwww.pr.control.eval.domain.model.GoldenReviewAction;
import com.objwww.pr.control.eval.domain.model.GoldenReviewEvent;
import com.objwww.pr.control.eval.domain.repository.GoldenCandidateRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * golden_candidate / golden_review_event 的 Postgres 实现（V22；M5-03）。
 *
 * <p>事务边界：insertDraft（候选 + PROPOSED 事件）与 casTransition（状态机 UPDATE
 * + 事件 INSERT）都在同一事务内提交——"两签同事务"的落地面。casTransition 以
 * (id, expectedState, revision) 三重 CAS 命中才写，0 行 = 旁路/并发修改返回
 * false（不改任何状态）。事件表只 insert（append-only），UPDATE/DELETE 由授权面
 * 与本类共同封死。
 */
public class PostgresGoldenCandidateRepository implements GoldenCandidateRepository {

    private static final String INSERT_CANDIDATE_SQL = """
            INSERT INTO golden_candidate (
                id, case_version_id, proposed_gt, reason, state, proposed_by,
                reviewer_a, reviewer_b, revision, created_at, updated_at
            ) VALUES (
                :id, :caseVersionId, CAST(:proposedGt AS jsonb), :reason, :state, :proposedBy,
                :reviewerA, :reviewerB, :revision, :createdAt, :updatedAt
            )
            """;

    private static final String UPDATE_CANDIDATE_SQL = """
            UPDATE golden_candidate
               SET state = :state, reviewer_a = :reviewerA, reviewer_b = :reviewerB,
                   revision = revision + 1, updated_at = :updatedAt
             WHERE id = :id AND state = :expectedState AND revision = :expectedRevision
            """;

    private static final String INSERT_EVENT_SQL = """
            INSERT INTO golden_review_event (
                id, candidate_id, action, actor, expected_revision, idempotency_key,
                payload, created_at
            ) VALUES (
                :id, :candidateId, :action, :actor, :expectedRevision, :idempotencyKey,
                CAST(:payload AS jsonb), :createdAt
            )
            """;

    private static final String FIND_CANDIDATE_SQL = """
            SELECT id, case_version_id, proposed_gt, reason, state, proposed_by,
                   reviewer_a, reviewer_b, revision, created_at, updated_at
            FROM golden_candidate
            WHERE id = :id
            """;

    private static final String FIND_EVENT_SQL = """
            SELECT id, candidate_id, action, actor, expected_revision, idempotency_key,
                   payload, created_at
            FROM golden_review_event
            WHERE candidate_id = :candidateId
            ORDER BY created_at, id
            """;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public PostgresGoldenCandidateRepository(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource 不得为 null");
        this.jdbc = JdbcClient.create(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public void insertDraft(GoldenCandidate candidate, GoldenReviewEvent event) {
        tx.executeWithoutResult(status -> {
            jdbc.sql(INSERT_CANDIDATE_SQL)
                    .param("id", candidate.id())
                    .param("caseVersionId", candidate.caseVersionId())
                    .param("proposedGt", writeJson(candidate.proposedGt()))
                    .param("reason", candidate.reason())
                    .param("state", candidate.state().name())
                    .param("proposedBy", candidate.proposedBy())
                    .param("reviewerA", candidate.reviewerA())
                    .param("reviewerB", candidate.reviewerB())
                    .param("revision", candidate.revision())
                    .param("createdAt", Timestamp.from(candidate.createdAt()))
                    .param("updatedAt", Timestamp.from(candidate.updatedAt()))
                    .update();
            insertEvent(event);
        });
    }

    @Override
    public Optional<GoldenCandidate> find(UUID id) {
        return jdbc.sql(FIND_CANDIDATE_SQL)
                .param("id", id)
                .query((rs, rowNum) -> new GoldenCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getObject("case_version_id", UUID.class),
                        readMap(rs.getString("proposed_gt")),
                        rs.getString("reason"),
                        GoldenCandidateState.valueOf(rs.getString("state")),
                        rs.getString("proposed_by"),
                        rs.getString("reviewer_a"),
                        rs.getString("reviewer_b"),
                        rs.getLong("revision"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    @Override
    public boolean eventExists(String idempotencyKey) {
        Boolean exists = jdbc.sql("SELECT exists(SELECT 1 FROM golden_review_event"
                        + " WHERE idempotency_key = :key)")
                .param("key", idempotencyKey)
                .query(Boolean.class).single();
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public boolean casTransition(GoldenCandidate next, GoldenCandidateState expectedState,
                                 GoldenReviewEvent event) {
        Boolean applied = tx.execute(status -> {
            int updated = jdbc.sql(UPDATE_CANDIDATE_SQL)
                    .param("state", next.state().name())
                    .param("reviewerA", next.reviewerA())
                    .param("reviewerB", next.reviewerB())
                    .param("updatedAt", Timestamp.from(next.updatedAt()))
                    .param("id", next.id())
                    .param("expectedState", expectedState.name())
                    .param("expectedRevision", event.expectedRevision())
                    .update();
            if (updated == 0) {
                status.setRollbackOnly();
                return false;
            }
            insertEvent(event);
            return true;
        });
        return Boolean.TRUE.equals(applied);
    }

    @Override
    public List<GoldenReviewEvent> events(UUID candidateId) {
        return jdbc.sql(FIND_EVENT_SQL)
                .param("candidateId", candidateId)
                .query((rs, rowNum) -> new GoldenReviewEvent(
                        rs.getObject("id", UUID.class),
                        rs.getObject("candidate_id", UUID.class),
                        GoldenReviewAction.valueOf(rs.getString("action")),
                        rs.getString("actor"),
                        rs.getLong("expected_revision"),
                        rs.getString("idempotency_key"),
                        readMap(rs.getString("payload")),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private void insertEvent(GoldenReviewEvent event) {
        try {
            jdbc.sql(INSERT_EVENT_SQL)
                    .param("id", event.id())
                    .param("candidateId", event.candidateId())
                    .param("action", event.action().name())
                    .param("actor", event.actor())
                    .param("expectedRevision", event.expectedRevision())
                    .param("idempotencyKey", event.idempotencyKey())
                    .param("payload", writeJson(event.payload()))
                    .param("createdAt", Timestamp.from(event.createdAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            // CAS 命中后同事务撞 idempotency 唯一键 = 另一逻辑操作盗用了该键：
            // 不是重放（重放在服务层 eventExists 已短路），整事务回滚 fail-loud
            throw new IllegalStateException("复核事件 idempotency_key 冲突（非重放面）: "
                    + event.idempotencyKey(), e);
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("golden candidate json 序列化失败", e);
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return JSON.readValue(json, MAP_TYPE);
        } catch (IOException e) {
            throw new IllegalStateException("golden candidate json 反序列化失败", e);
        }
    }
}
