package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.TaskExecutionBinding;
import com.objwww.pr.control.alert.domain.repository.TaskExecutionBindingRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * rca_task_execution_binding 的 Postgres 实现（R7-X1，V46）。
 * 绑定=冻结事实：只增不改，uq(run_id, round_id, task_key) 冲突显式抛（幂等由
 * Supervisor 启动短路兜底，撞键=编译重入缺陷，不静默吞）。
 */
public class PostgresTaskExecutionBindingRepository implements TaskExecutionBindingRepository {

    private static final TypeReference<List<String>> LIST_STRING = new TypeReference<>() {
    };
    private static final TypeReference<java.util.LinkedHashMap<String, Object>> MAP =
            new TypeReference<>() {
            };

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PostgresTaskExecutionBindingRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void insert(TaskExecutionBinding b) {
        jdbc.sql("""
                INSERT INTO rca_task_execution_binding (
                    task_id, run_id, round_id, task_key,
                    role_id, role_version, role_digest,
                    release_digest, config_epoch,
                    input_refs, expected_output_schema,
                    parent_request_id, required, failure_policy, created_at
                ) VALUES (
                    :taskId, :runId, :roundId, :taskKey,
                    :roleId, :roleVersion, :roleDigest,
                    :releaseDigest, :configEpoch,
                    cast(:inputRefs as jsonb), cast(:outputSchema as jsonb),
                    :parentRequestId, :required, :failurePolicy, :createdAt
                )
                """)
                .param("taskId", b.taskId())
                .param("runId", b.runId())
                .param("roundId", b.roundId())
                .param("taskKey", b.taskKey())
                .param("roleId", b.roleId())
                .param("roleVersion", b.roleVersion())
                .param("roleDigest", b.roleDigest())
                .param("releaseDigest", b.releaseDigest())
                .param("configEpoch", b.configEpoch())
                .param("inputRefs", jsonOf(b.inputRefs()))
                .param("outputSchema", jsonOf(b.expectedOutputSchema()))
                .param("parentRequestId", b.parentRequestId())
                .param("required", b.required())
                .param("failurePolicy", b.failurePolicy().name())
                .param("createdAt", Timestamp.from(b.createdAt()))
                .update();
    }

    @Override
    public Optional<TaskExecutionBinding> findByTask(UUID taskId) {
        List<TaskExecutionBinding> rows = jdbc.sql("""
                SELECT * FROM rca_task_execution_binding WHERE task_id = :taskId
                """)
                .param("taskId", taskId)
                .query(this::mapRow)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<TaskExecutionBinding> findByRun(UUID runId) {
        return jdbc.sql("""
                SELECT * FROM rca_task_execution_binding
                 WHERE run_id = :runId
                 ORDER BY round_id, task_key, task_id
                """)
                .param("runId", runId)
                .query(this::mapRow)
                .list();
    }

    private TaskExecutionBinding mapRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new TaskExecutionBinding(
                rs.getObject("task_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getInt("round_id"),
                rs.getString("task_key"),
                rs.getString("role_id"),
                rs.getString("role_version"),
                rs.getString("role_digest"),
                rs.getString("release_digest"),
                rs.getObject("config_epoch") == null
                        ? null : rs.getLong("config_epoch"),
                listOf(rs.getString("input_refs")),
                mapOf(rs.getString("expected_output_schema")),
                rs.getObject("parent_request_id", UUID.class),
                rs.getBoolean("required"),
                TaskExecutionBinding.FailurePolicy.valueOf(rs.getString("failure_policy")),
                createdAt.toInstant());
    }

    private String jsonOf(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("jsonb 序列化失败: " + e.getMessage(), e);
        }
    }

    private List<String> listOf(String json) {
        try {
            return mapper.readValue(json, LIST_STRING);
        } catch (Exception e) {
            throw new IllegalStateException("jsonb 列表解析失败: " + e.getMessage(), e);
        }
    }

    private java.util.LinkedHashMap<String, Object> mapOf(String json) {
        try {
            return mapper.readValue(json, MAP);
        } catch (Exception e) {
            throw new IllegalStateException("jsonb 对象解析失败: " + e.getMessage(), e);
        }
    }
}
