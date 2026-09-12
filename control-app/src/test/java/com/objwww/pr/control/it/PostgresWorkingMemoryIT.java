package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.agent.WorkingMemory;
import com.objwww.pr.control.alert.domain.identity.InvestigationInputs;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresPrimaryCheckpointRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRunConfigEpochRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresWorkingMemory;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R10 工作记忆（真 PG，V91 rca_working_memory）：uq(run, task, checkpoint_revision)
 * 幂等 append（同修订重放返回既有行 = MC07/MC08，候选漂移不落第二行）、
 * latestByTask 最大修订（他任务零行如实 empty）、append-only 授权面
 * （control_app 无 update/delete、publisher_app 零授）、检查点 memory_id/
 * memory_digest 关联列回读（§19.2 钉面）。
 * 静态面由 EnMigrationContractTest.v91 锁（Docker 不可用时本地绿）。
 */
class PostgresWorkingMemoryIT extends PostgresITBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-11T09:00:00Z");

    private org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private PostgresIncidentRepository incidents;
    private PostgresRcaRunRepository rcaRuns;
    private PostgresWorkingMemory memories;
    private PostgresPrimaryCheckpointRepository checkpoints;

    @BeforeEach
    void setUpRepositories() {
        jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(controlDataSource());
        incidents = new PostgresIncidentRepository(jdbc);
        rcaRuns = new PostgresRcaRunRepository(jdbc,
                new PostgresRunConfigEpochRepository(jdbc));
        memories = new PostgresWorkingMemory(jdbc, MAPPER);
        checkpoints = new PostgresPrimaryCheckpointRepository(jdbc, MAPPER);
    }

    @Test
    @DisplayName("append 幂等：同修订重放返回既有行，候选漂移不落第二行（MC07/MC08）")
    void appendSameRevisionReturnsExistingRowAndDiscardsDriftedCandidate() {
        UUID[] runTask = mintRunTask("wm-replay");
        WorkingMemory first = memoryOf(runTask, 5, Map.of("hypotheses", List.of("候选A")));
        WorkingMemory replayed = memoryOf(runTask, 5, Map.of("hypotheses", List.of("候选B")));

        WorkingMemory committed = memories.append(first);
        WorkingMemory again = memories.append(replayed);

        assertThat(committed.id()).isEqualTo(first.id());
        assertThat(again.id()).as("同修订重放返回既有行").isEqualTo(committed.id());
        assertThat(again.slots().get("hypotheses")).as("候选漂移被丢弃")
                .containsExactly("候选A");
        assertThat(rowCount(runTask[1])).as("不落第二行").isEqualTo(1);
    }

    @Test
    @DisplayName("latestByTask 取最大修订；未知任务零行如实 empty")
    void latestByTaskPicksHighestRevisionAndEmptyForUnknownTask() {
        UUID[] runTask = mintRunTask("wm-latest");
        memories.append(memoryOf(runTask, 1, Map.of("hypotheses", List.of("旧"))));
        memories.append(memoryOf(runTask, 2, Map.of("hypotheses", List.of("新"))));

        WorkingMemory latest = memories.latestByTask(runTask[0], runTask[1]).orElseThrow();

        assertThat(latest.checkpointRevision()).isEqualTo(2);
        assertThat(latest.slots().get("hypotheses")).containsExactly("新");
        assertThat(memories.latestByTask(runTask[0], UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("uq(run,task,revision)：裸 SQL 重复插入被唯一约束拒绝（仓储先查是优化非防线）")
    void rawDuplicateInsertHitsUniqueConstraint() {
        UUID[] runTask = mintRunTask("wm-uq");
        memories.append(memoryOf(runTask, 0, Map.of()));

        assertThatThrownBy(() -> jdbc.sql("""
                        insert into rca_working_memory (id, run_id, task_id,
                            checkpoint_revision, memory_json, memory_digest, created_at)
                        values (:id, :run, :task, 0, cast('{}' as jsonb), :digest, now())
                        """)
                .param("id", UUID.randomUUID()).param("run", runTask[0])
                .param("task", runTask[1])
                .param("digest", "d".repeat(64)).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_rca_working_memory_revision");
    }

    @Test
    @DisplayName("append-only 授权面：control_app 无 update/delete；publisher_app 零授")
    void appendOnlyGrantsDenyUpdateDeleteAndCrossRoleRead() {
        UUID[] runTask = mintRunTask("wm-grants");
        WorkingMemory row =
                memories.append(memoryOf(runTask, 4, Map.of("open_gaps", List.of("g"))));

        assertThatThrownBy(() -> jdbc.sql(
                        "update rca_working_memory set memory_json = '{}' where id = :id")
                .param("id", row.id()).update())
                .isInstanceOfSatisfying(DataAccessException.class,
                        e -> assertThat(sqlStateOf(e)).isEqualTo("42501"));
        assertThatThrownBy(() -> jdbc.sql(
                        "delete from rca_working_memory where id = :id")
                .param("id", row.id()).update())
                .isInstanceOfSatisfying(DataAccessException.class,
                        e -> assertThat(sqlStateOf(e)).isEqualTo("42501"));
        assertThatThrownBy(() -> publisherJdbc.sql(
                        "select memory_digest from rca_working_memory")
                .query((rs, i) -> rs.getString(1)).list())
                .isInstanceOfSatisfying(DataAccessException.class,
                        e -> assertThat(sqlStateOf(e)).isEqualTo("42501"));
    }

    @Test
    @DisplayName("V91 检查点关联列：upsert 带记忆锚回读一致（§19.2 钉面，MC07 恢复基础）")
    void checkpointMemoryAnchorRoundTrips() {
        UUID[] runTask = mintRunTask("wm-checkpoint");
        WorkingMemory row =
                memories.append(memoryOf(runTask, 1, Map.of("ruled_out", List.of("r"))));

        PrimaryCheckpoint advanced = PrimaryCheckpoint
                .initial(runTask[1], runTask[0], 0, Instant.now())
                .withStepAdvanced(Digest.sha256Of("snap").hex(), row.id(),
                        row.memoryDigest(), null, Instant.now());
        checkpoints.upsert(advanced);

        PrimaryCheckpoint reloaded = checkpoints.findByTask(runTask[1]).orElseThrow();
        assertThat(reloaded.memoryId()).isEqualTo(row.id());
        assertThat(reloaded.memoryDigest()).isEqualTo(row.memoryDigest());
        assertThat(reloaded.inputSnapshotDigest())
                .as("char(64) 摘要列读回不重不漏（真 digest 恰 64 位）")
                .isEqualTo(Digest.sha256Of("snap").hex());
    }

    // ------------------------------------------------------------------ 夹具

    private WorkingMemory memoryOf(UUID[] runTask, long revision,
            Map<String, List<String>> slots) {
        return WorkingMemory.of(UUID.randomUUID(), runTask[0], runTask[1], revision,
                slots, null, NOW);
    }

    private long rowCount(UUID taskId) {
        return jdbc.sql("select count(*) from rca_working_memory where task_id = :task")
                .param("task", taskId)
                .query((rs, i) -> rs.getLong(1)).list().get(0);
    }

    /** 40xxx 系异常剥 PSQLException 的 SQLState（42501 = insufficient_privilege） */
    private static String sqlStateOf(Exception e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof PSQLException psql) {
                return psql.getSQLState();
            }
            t = t.getCause();
        }
        return null;
    }

    /** 真 run + task（双 FK 实在面）；返回 [runId, taskId] */
    private UUID[] mintRunTask(String tag) {
        Incident incident = insertIncident(tag);
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        RcaRun run = new RcaRun(runId, incident.id(), 0, RunTrigger.INITIAL,
                RcaRunState.QUEUED, Digest.sha256Of("run-" + tag),
                now.minus(Duration.ofMinutes(4)), now, null, null, null);
        rcaRuns.insertRouted(run, new RcaRunRouting(RcaEngine.NATIVE,
                Digest.sha256Of(tag + "-bundle"), incident.incidentKey(), 37,
                "IT_MEMORY"), InvestigationInputs.freezeAt(incident, now));
        UUID taskId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO rca_task (id, run_id, task_key, state,
                    available_at, ready_since, deadline_at, created_at, updated_at)
                VALUES (:id, :run, :key, 'DONE', now(), now(), now(), now(), now())
                """)
                .param("id", taskId).param("run", runId)
                .param("key", "WM_IT_" + taskId.toString().substring(0, 8))
                .update();
        return new UUID[]{runId, taskId};
    }

    private Incident insertIncident(String tag) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Incident incident = new Incident(id, "alertname=HighErrorRate|service=" + tag,
                IncidentStatus.FIRING, 0, now.minus(Duration.ofMinutes(5)),
                now.minus(Duration.ofMinutes(5)), null, null, null, 0, 0, 0, null,
                now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(5)), now, now);
        incidents.insert(incident);
        return incident;
    }
}
