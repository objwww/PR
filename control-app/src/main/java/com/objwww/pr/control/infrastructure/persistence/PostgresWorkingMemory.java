package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.agent.WorkingMemory;
import com.objwww.pr.control.alert.domain.repository.WorkingMemoryPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * rca_working_memory 的 Postgres 实现（R10，V91 append-only 深冻结）：
 * uq(run,task,checkpoint_revision) 冲突 → 返回既有行（候选丢弃，已提交快照
 * 不漂移——MC06/MC08）；最近快照按 revision 降序取一。
 */
public class PostgresWorkingMemory implements WorkingMemoryPort {

    private static final TypeReference<Map<String, List<String>>> SLOTS =
            new TypeReference<>() {
            };

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PostgresWorkingMemory(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public WorkingMemory append(WorkingMemory candidate) {
        List<UUID> existing = jdbc.sql("""
                select id from rca_working_memory
                where run_id = :runId and task_id = :taskId
                  and checkpoint_revision = :revision
                """)
                .param("runId", candidate.runId())
                .param("taskId", candidate.taskId())
                .param("revision", candidate.checkpointRevision())
                .query((rs, i) -> rs.getObject("id", UUID.class))
                .list();
        if (!existing.isEmpty()) {
            return findById(existing.get(0));
        }
        jdbc.sql("""
                insert into rca_working_memory (id, run_id, task_id, checkpoint_revision,
                    memory_json, memory_digest, config_epoch, created_at)
                values (:id, :runId, :taskId, :revision,
                    cast(:slots as jsonb), :digest, :configEpoch, :createdAt)
                """)
                .param("id", candidate.id())
                .param("runId", candidate.runId())
                .param("taskId", candidate.taskId())
                .param("revision", candidate.checkpointRevision())
                .param("slots", jsonOf(candidate.slots()))
                .param("digest", candidate.memoryDigest())
                .param("configEpoch", candidate.configEpoch())
                .param("createdAt", Timestamp.from(candidate.createdAt()))
                .update();
        return candidate;
    }

    @Override
    public Optional<WorkingMemory> latestByTask(UUID runId, UUID taskId) {
        List<WorkingMemory> rows = jdbc.sql("""
                select id, run_id, task_id, checkpoint_revision, memory_json,
                       memory_digest, config_epoch, created_at
                from rca_working_memory
                where run_id = :runId and task_id = :taskId
                order by checkpoint_revision desc
                limit 1
                """)
                .param("runId", runId)
                .param("taskId", taskId)
                .query(this::mapRow)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private WorkingMemory findById(UUID id) {
        return jdbc.sql("""
                        select id, run_id, task_id, checkpoint_revision, memory_json,
                               memory_digest, config_epoch, created_at
                        from rca_working_memory where id = :id
                        """)
                .param("id", id)
                .query(this::mapRow)
                .list()
                .get(0);
    }

    private WorkingMemory mapRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        long revision = rs.getLong("checkpoint_revision");
        Long configEpoch = (Long) rs.getObject("config_epoch");
        Map<String, List<String>> slots = slotsOf(rs.getString("memory_json"));
        return new WorkingMemory(rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("task_id", UUID.class),
                revision, slots, rs.getString("memory_digest"),
                configEpoch, createdAt.toInstant());
    }

    private String jsonOf(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("memory_json 序列化失败: " + e.getMessage(), e);
        }
    }

    private Map<String, List<String>> slotsOf(String json) {
        try {
            return mapper.readValue(json, SLOTS);
        } catch (Exception e) {
            throw new IllegalStateException("memory_json 解析失败: " + e.getMessage(), e);
        }
    }
}
