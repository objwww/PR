package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaEventAppender;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M4-10/11/12 真 PG 统一事件账本（V14 rca_event）：并发追加每 run seq 连续单调、
 * 状态事务回滚事件必回滚（无空洞）、同 event_id+digest 重放幂等、digest 异显式冲突、
 * 进度事件独立短事务（REQUIRES_NEW 存活证明）、rca_agent_event 只读视图一致且写入被拒。
 */
class AlertRcaEventIT extends PostgresITBase {

    private RcaEventAppender appender;

    @Override
    @BeforeEach
    void truncateAll() {
        adminJdbc.sql("""
                TRUNCATE pr_subject, pr_revision, review_run, run_step, work_item, step_attempt,
                    execution_event, outbox_command, outbox_dependency, publication_resource,
                    review_finding, artifact, webhook_inbox, step_checkpoint, repair_request,
                    model_call_ledger, tool_call, sandbox_job, artifact_grant,
                    alert_inbox, alert_event, incident, rca_run, rca_task, rca_attempt,
                    rca_report, external_invocation_ledger, rca_task_edge,
                    run_budget_state, run_budget_entry, incident_budget_entry, rca_event
                RESTART IDENTITY CASCADE
                """).update();
        appender = new PostgresRcaEventAppender(controlJdbc, controlTx,
                requiresNewTemplate());
    }

    /** 与生产 PersistenceConfig 同构的 REQUIRES_NEW 模板（进度事件独立短事务） */
    private org.springframework.transaction.support.TransactionTemplate requiresNewTemplate() {
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                controlDataSource()));
        template.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private RcaEventAppender.EventDraft draft(String marker) {
        return new RcaEventAppender.EventDraft(UUID.randomUUID(), "TEST_EVENT",
                "{\"marker\":\"" + marker + "\"}");
    }

    private RcaEventAppender.EventDraft sameIdDraft(UUID eventId, String marker) {
        return new RcaEventAppender.EventDraft(eventId, "TEST_EVENT",
                "{\"marker\":\"" + marker + "\"}");
    }

    private UUID seedRun() {
        UUID incident = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO incident(id, incident_key, status, generation, episode_started_at,
                    first_seen_at, last_event_at, created_at, updated_at)
                VALUES (:id, :key, 'FIRING', 0, now(), now(), now(), now(), now())
                """).param("id", incident)
                .param("key", "alertname=HighErrorRate|service=edge-" + incident).update();
        UUID run = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO rca_run(id, incident_id, generation, trigger_kind, state,
                    investigation_hash, created_at, updated_at)
                VALUES (:id, :inc, 0, 'INITIAL', 'QUEUED', :hash, now(), now())
                """).param("id", run).param("inc", incident)
                .param("hash", Digest.sha256Of("it-" + run).value()).update();
        return run;
    }

    private long lastEventSeq(UUID run) {
        return controlJdbc.sql("SELECT last_event_seq FROM rca_run WHERE id = :id")
                .param("id", run).query(Long.class).single();
    }

    @Test
    void itE01_concurrentAppendsGetContiguousMonotonicSeqs() throws Exception {
        UUID run = seedRun();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Long> seqs = java.util.Collections.synchronizedList(new ArrayList<>());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    seqs.add(appender.appendIndependent(run, draft("e")));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            List<Long> sorted = new ArrayList<>(seqs);
            java.util.Collections.sort(sorted);
            assertThat(sorted).containsExactlyElementsOf(
                    java.util.stream.LongStream.rangeClosed(1, 40).boxed().toList());
            assertThat(lastEventSeq(run)).isEqualTo(40);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void itE02_stateTxRollbackRollsBackEventAndSeqWithoutGap() {
        UUID run = seedRun();
        assertThatThrownBy(() -> controlTx.execute(status -> {
            appender.append(run, draft("state-fact"));
            assertThat(lastEventSeq(run)).isEqualTo(1); // 事务内可见
            throw new IllegalStateException("状态落库失败");
        })).isInstanceOf(IllegalStateException.class);
        // 状态事务回滚 → 事件与 seq 一并回滚
        assertThat(count("rca_event")).isZero();
        assertThat(lastEventSeq(run)).isZero();
        // 无空洞：下一次追加仍从 1 起
        assertThat(appender.append(run, draft("retry"))).isEqualTo(1);
    }

    @Test
    void itE03_replayWithSameDigestIsIdempotent() {
        UUID run = seedRun();
        UUID eventId = UUID.randomUUID();
        long first = appender.append(run, sameIdDraft(eventId, "same"));
        long replay = appender.append(run, sameIdDraft(eventId, "same"));
        assertThat(replay).isEqualTo(first);
        assertThat(count("rca_event")).isEqualTo(1);
        assertThat(lastEventSeq(run)).isEqualTo(1); // 幂等重放不推进 seq
    }

    @Test
    void itE04_sameEventIdDifferentDigestIsExplicitConflict() {
        UUID run = seedRun();
        UUID eventId = UUID.randomUUID();
        appender.append(run, sameIdDraft(eventId, "v1"));
        assertThatThrownBy(() -> appender.append(run, sameIdDraft(eventId, "v2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("冲突");
        assertThat(count("rca_event")).isEqualTo(1);
        assertThat(lastEventSeq(run)).isEqualTo(1);
    }

    @Test
    void itE05_progressEventSurvivesCallerRollback() {
        UUID run = seedRun();
        assertThatThrownBy(() -> controlTx.execute(status -> {
            appender.appendIndependent(run, draft("progress")); // REQUIRES_NEW 立即提交
            throw new IllegalStateException("外层状态回滚");
        })).isInstanceOf(IllegalStateException.class);
        // 进度事件已独立提交（外层回滚不影响）；seq 已消耗 1
        assertThat(count("rca_event")).isEqualTo(1);
        assertThat(lastEventSeq(run)).isEqualTo(1);
    }

    @Test
    void itE06_agentEventViewMatchesAndRejectsWrites() {
        UUID run = seedRun();
        appender.append(run, draft("a"));
        appender.append(run, draft("b"));
        // 视图结果与权威表一致
        Long viewCount = controlJdbc.sql(
                        "SELECT count(*) FROM rca_agent_event WHERE run_id = :run")
                .param("run", run).query(Long.class).single();
        assertThat(viewCount).isEqualTo(2);
        // 写入被拒（应用角色对视图仅 SELECT）
        assertThat(chainContains(() -> controlJdbc.sql("""
                        INSERT INTO rca_agent_event(run_id, seq, event_id, event_type, payload,
                            payload_digest)
                        VALUES (:run, 99, :eid, 'X', CAST('{}' AS jsonb), 'd')
                        """)
                .param("run", run).param("eid", UUID.randomUUID()).update(),
                "permission denied")).isTrue();
    }

    @Test
    void itE07_appendToMissingRunFailsExplicitly() {
        assertThatThrownBy(() -> appender.append(UUID.randomUUID(), draft("orphan")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run 不存在");
        assertThat(count("rca_event")).isZero();
    }

    private boolean chainContains(Runnable action, String fragment) {
        try {
            action.run();
        } catch (RuntimeException e) {
            StringBuilder chain = new StringBuilder();
            for (Throwable c = e; c != null; c = c.getCause()) {
                chain.append(c.getMessage()).append('\n');
            }
            return chain.toString().contains(fragment);
        }
        return false;
    }
}
