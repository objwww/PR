package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.agent.ContextSummary;
import com.objwww.pr.control.alert.domain.identity.InvestigationInputs;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.infrastructure.persistence.PostgresContextSummary;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRunConfigEpochRepository;
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
 * R11 上下文摘要（真 PG，V92 rca_context_summary）：uq(run, task,
 * source_snapshot_digest) CAS 提交（同源重放返回既有行）、latestByTask/countByRun/
 * countByTask 读面、不可变档授权面（control_app 无 update/delete、publisher_app
 * 零授）、jsonb refs 与正文列回读一致。
 * 静态面由 EnMigrationContractTest.v92 锁（Docker 不可用时本地绿）。
 */
class PostgresContextSummaryIT extends PostgresITBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-11T09:00:00Z");

    private org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private PostgresIncidentRepository incidents;
    private PostgresRcaRunRepository rcaRuns;
    private PostgresContextSummary summaries;

    @BeforeEach
    void setUpRepositories() {
        jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(controlDataSource());
        incidents = new PostgresIncidentRepository(jdbc);
        rcaRuns = new PostgresRcaRunRepository(jdbc,
                new PostgresRunConfigEpochRepository(jdbc));
        summaries = new PostgresContextSummary(jdbc, MAPPER);
    }

    @Test
    @DisplayName("CAS 提交：同源重放返回既有行；字段与 jsonb refs 回读一致")
    void appendSameSourceReturnsExistingRowAndRoundTrips() {
        UUID[] runTask = mintRunTask("cs-cas");
        String source = Digest.sha256Of("src-digest-a").hex();
        ContextSummary first = summaryOf(runTask, source, 3,
                List.of("ref-1"), List.of("ref-2"));
        ContextSummary replay = summaryOf(runTask, source, 3,
                List.of("漂移"), List.of());

        ContextSummary committed = summaries.append(first);
        ContextSummary again = summaries.append(replay);

        assertThat(committed.id()).isEqualTo(first.id());
        assertThat(again.id()).as("同源 CAS：候选丢弃返回既有行").isEqualTo(first.id());
        assertThat(again.summaryText()).isEqualTo(first.summaryText());

        var loaded = summaries.findBySource(runTask[0], runTask[1], source)
                .orElseThrow();
        assertThat(loaded.requiredRefs()).containsExactly("ref-1");
        assertThat(loaded.omittedRefs()).containsExactly("ref-2");
        assertThat(loaded.summaryDigest())
                .isEqualTo(Digest.sha256Of(first.summaryText()).value());
        assertThat(loaded.eventSeqFrom()).isZero();
        assertThat(loaded.eventSeqTo()).isEqualTo(3);
        assertThat(loaded.configEpoch()).isNull();
        assertThat(loaded.meta()).containsEntry("validation_result", "REFS_VALIDATED");
    }

    @Test
    @DisplayName("读面：latestByTask 取最近提交；countByRun/countByTask 分域计数")
    void readFacesLatestAndCounts() {
        UUID[] runTask = mintRunTask("cs-read");
        String sourceOne = Digest.sha256Of("src-1").hex();
        String sourceTwo = Digest.sha256Of("src-2").hex();
        summaries.append(summaryOf(runTask, sourceOne, 1, List.of(), List.of()));
        summaries.append(summaryOf(runTask, sourceTwo, 2, List.of(), List.of()));

        var latest = summaries.latestByTask(runTask[0], runTask[1]).orElseThrow();
        assertThat(latest.sourceSnapshotDigest())
                .as("char(64) 摘要列读回不重不漏（真 digest 恰 64 位）")
                .isEqualTo(sourceTwo);
        assertThat(summaries.countByRun(runTask[0])).isEqualTo(2);
        assertThat(summaries.countByTask(runTask[0], runTask[1])).isEqualTo(2);
        assertThat(summaries.countByTask(runTask[0], UUID.randomUUID())).isZero();
        assertThat(summaries.latestByTask(runTask[0], UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("uq(run,task,source)：裸 SQL 重复插入被唯一约束拒绝（先查是优化非防线）")
    void rawDuplicateInsertHitsUniqueConstraint() {
        UUID[] runTask = mintRunTask("cs-uq");
        summaries.append(summaryOf(runTask, "src-dup", 0, List.of(), List.of()));

        assertThatThrownBy(() -> jdbc.sql("""
                        insert into rca_context_summary (id, run_id, task_id, schema_version,
                            source_snapshot_digest, event_seq_from, event_seq_to,
                            summary_prompt_digest, model, token_before, token_after,
                            required_refs, omitted_refs, summary_text, summary_digest,
                            validation_result, producer, created_at)
                        values (:id, :run, :task, 1, 'src-dup', 0, 0, 'pd', 'm', 10, 5,
                            '[]'::jsonb, '[]'::jsonb, 'x', :digest, 'REFS_VALIDATED',
                            'it', now())
                        """)
                .param("id", UUID.randomUUID()).param("run", runTask[0])
                .param("task", runTask[1])
                .param("digest", "d".repeat(64)).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_rca_context_summary_source");
    }

    @Test
    @DisplayName("不可变档授权面：control_app 无 update/delete；publisher_app 零授")
    void immutableGrantsDenyUpdateDeleteAndCrossRoleRead() {
        UUID[] runTask = mintRunTask("cs-grants");
        ContextSummary row = summaries.append(summaryOf(runTask, "src-grants", 4,
                List.of(), List.of()));

        assertThatThrownBy(() -> jdbc.sql(
                        "update rca_context_summary set summary_text = '篡改' where id = :id")
                .param("id", row.id()).update())
                .isInstanceOfSatisfying(DataAccessException.class,
                        e -> assertThat(sqlStateOf(e)).isEqualTo("42501"));
        assertThatThrownBy(() -> jdbc.sql(
                        "delete from rca_context_summary where id = :id")
                .param("id", row.id()).update())
                .isInstanceOfSatisfying(DataAccessException.class,
                        e -> assertThat(sqlStateOf(e)).isEqualTo("42501"));
        assertThatThrownBy(() -> publisherJdbc.sql(
                        "select summary_digest from rca_context_summary")
                .query((rs, i) -> rs.getString(1)).list())
                .isInstanceOfSatisfying(DataAccessException.class,
                        e -> assertThat(sqlStateOf(e)).isEqualTo("42501"));
    }

    // ------------------------------------------------------------------ 夹具

    private ContextSummary summaryOf(UUID[] runTask, String source, long eventSeqTo,
            List<String> requiredRefs, List<String> omittedRefs) {
        // created_at 逐行递增：latestByTask 以 created_at 取最新，同刻提交并列无序
        return ContextSummary.of(UUID.randomUUID(), runTask[0], runTask[1], 1, source,
                0, eventSeqTo, "prompt-digest", "model-rca", 600, 120,
                requiredRefs, omittedRefs, "压缩后的调查上下文", "REFS_VALIDATED",
                "llm:route-rca", null, NOW.plusSeconds(eventSeqTo));
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
                "IT_CSUM"), InvestigationInputs.freezeAt(incident, now));
        UUID taskId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO rca_task (id, run_id, task_key, state,
                    available_at, ready_since, deadline_at, created_at, updated_at)
                VALUES (:id, :run, :key, 'DONE', now(), now(), now(), now(), now())
                """)
                .param("id", taskId).param("run", runId)
                .param("key", "CS_IT_" + taskId.toString().substring(0, 8))
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
