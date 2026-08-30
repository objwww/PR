package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.model.RunMode;
import com.objwww.pr.control.domain.repository.ReviewRunRepository;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.RunState;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ReviewRunRepository 的 Postgres 实现。
 * save = INSERT / ON CONFLICT (id) UPDATE 状态字段的 upsert；run_key 唯一冲突（B-3 webhook 重投）
 * 不经 ON CONFLICT 吸收——以约束冲突异常上抛，由编排层捕获后幂等返回。
 *
 * <p>刻意不加 Spring 注解：接线见 infrastructure/config/PersistenceConfig（docker profile）。
 */
public class PostgresReviewRunRepository implements ReviewRunRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO review_run (
                id, pr_revision_id, parent_run_id, root_run_id,
                run_key, trigger_key, run_mode,
                policy_version, prompt_version, toolset_version, initial_model_route,
                state, publisher_disabled,
                token_budget, cost_budget_micros, deadline_at,
                version, created_at, updated_at, completed_at
            ) VALUES (
                :id, :prRevisionId, :parentRunId, :rootRunId,
                :runKey, :triggerKey, :runMode,
                :policyVersion, :promptVersion, :toolsetVersion, :initialModelRoute,
                :state, :publisherDisabled,
                :tokenBudget, :costBudgetMicros, :deadlineAt,
                :version, :createdAt, :updatedAt, :completedAt
            )
            ON CONFLICT (id) DO UPDATE SET
                state        = EXCLUDED.state,
                version      = review_run.version + 1,
                updated_at   = EXCLUDED.updated_at,
                completed_at = EXCLUDED.completed_at
            """;

    private static final String SELECT_COLUMNS = """
            id, pr_revision_id, parent_run_id, root_run_id,
            run_key, trigger_key, run_mode,
            policy_version, prompt_version, toolset_version, initial_model_route,
            state, publisher_disabled,
            token_budget, cost_budget_micros, deadline_at,
            version, created_at, updated_at, completed_at
            """;

    /** 非终态集合与 V1 ix_review_run_active 部分索引一致 */
    private static final String ACTIVE_STATES =
            "('CREATED','SNAPSHOTTING','REVIEWING','REVIEW_COMPLETE','PATCH_PROPOSED',"
                    + "'WAITING_APPROVAL','VERIFYING','READY_TO_PUBLISH','PUBLISHING')";

    private final JdbcClient jdbc;

    public PostgresReviewRunRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void save(ReviewRun run) {
        Objects.requireNonNull(run, "run");
        jdbc.sql(UPSERT_SQL)
                .param("id", run.getId())
                .param("prRevisionId", run.getPrRevisionId())
                .param("parentRunId", run.getParentRunId())
                .param("rootRunId", run.getRootRunId())
                .param("runKey", run.getRunKey().value())
                .param("triggerKey", run.getTriggerKey())
                .param("runMode", run.getRunMode().name())
                .param("policyVersion", run.getPolicyVersion())
                .param("promptVersion", run.getPromptVersion())
                .param("toolsetVersion", run.getToolsetVersion())
                .param("initialModelRoute", run.getInitialModelRoute())
                .param("state", run.getState().name())
                .param("publisherDisabled", run.isPublisherDisabled())
                .param("tokenBudget", run.getTokenBudget())
                .param("costBudgetMicros", run.getCostBudgetMicros())
                .param("deadlineAt", run.getDeadlineAt() == null ? null : Timestamp.from(run.getDeadlineAt()))
                .param("version", run.getVersion())
                .param("createdAt", Timestamp.from(run.getCreatedAt()))
                .param("updatedAt", Timestamp.from(run.getUpdatedAt()))
                .param("completedAt", run.getCompletedAt() == null ? null : Timestamp.from(run.getCompletedAt()))
                .update();
    }

    @Override
    public Optional<ReviewRun> findById(UUID id) {
        return jdbc.sql("SELECT " + SELECT_COLUMNS + " FROM review_run WHERE id = :id")
                .param("id", Objects.requireNonNull(id))
                .query(this::map)
                .optional();
    }

    @Override
    public Optional<ReviewRun> findByRunKey(Digest runKey) {
        return jdbc.sql("SELECT " + SELECT_COLUMNS + " FROM review_run WHERE run_key = :runKey")
                .param("runKey", Objects.requireNonNull(runKey).value())
                .query(this::map)
                .optional();
    }

    @Override
    public List<ReviewRun> findActiveByPrSubjectId(UUID prSubjectId) {
        // r.* 避免与 pr_revision 同名列歧义；映射读的是 review_run 列名
        return jdbc.sql("SELECT r.*" + """
                        \sFROM review_run r
                        JOIN pr_revision rev ON rev.id = r.pr_revision_id
                        WHERE rev.pr_subject_id = :prSubjectId
                          AND r.state IN\s""" + ACTIVE_STATES + """
                        ORDER BY r.created_at
                        """)
                .param("prSubjectId", Objects.requireNonNull(prSubjectId))
                .query(this::map)
                .list();
    }

    private ReviewRun map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp deadlineAt = rs.getTimestamp("deadline_at");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        long tokenBudget = rs.getLong("token_budget");
        boolean tokenBudgetNull = rs.wasNull();
        long costBudgetMicros = rs.getLong("cost_budget_micros");
        boolean costBudgetMicrosNull = rs.wasNull();
        return new ReviewRun(
                rs.getObject("id", UUID.class),
                rs.getObject("pr_revision_id", UUID.class),
                rs.getObject("parent_run_id", UUID.class),
                rs.getObject("root_run_id", UUID.class),
                new Digest(rs.getString("run_key")),
                rs.getString("trigger_key"),
                RunMode.valueOf(rs.getString("run_mode")),
                rs.getString("policy_version"),
                rs.getString("prompt_version"),
                rs.getString("toolset_version"),
                rs.getString("initial_model_route"),
                RunState.valueOf(rs.getString("state")),
                rs.getBoolean("publisher_disabled"),
                tokenBudgetNull ? null : tokenBudget,
                costBudgetMicrosNull ? null : costBudgetMicros,
                deadlineAt == null ? null : deadlineAt.toInstant(),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant());
    }
}
