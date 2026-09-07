package com.objwww.pr.control.it;

import com.objwww.pr.control.infrastructure.persistence.PostgresPartitionCatalog;
import com.objwww.pr.control.infrastructure.persistence.PostgresRetentionPolicyRepository;
import com.objwww.pr.control.ops.application.RetentionService;
import com.objwww.pr.control.ops.domain.model.RetentionPolicy;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M5-18 真 PG 分区面（V28/V29；方案 §12.1 L2，本机无 Docker 自动跳过）：
 * 分区路由（跨月落各自分区、父表跨月查询结果不变）、唯一约束并入分区键的放宽面
 * （E-17 坑：跨月同 (run_id,seq) 可容、同月重复仍拒）、策略链 insert-only、
 * legal hold 阻断一切清理候选（INV-AM5-9）与 release 单列开口。
 */
class PostgresRetentionIT extends PostgresITBase {

    private static final Instant CLOCK = Instant.parse("2027-01-01T00:00:00Z");

    private PostgresRetentionPolicyRepository policies;
    private RetentionService service;

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
                    run_budget_state, run_budget_entry, incident_budget_entry, rca_event,
                    retention_policy, legal_hold, archive_manifest
                RESTART IDENTITY CASCADE
                """).update();
        policies = new PostgresRetentionPolicyRepository(controlJdbc);
        service = new RetentionService(policies, new PostgresPartitionCatalog(controlJdbc),
                () -> CLOCK);
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

    private void seedEvent(UUID run, long seq, String createdAt) {
        controlJdbc.sql("""
                INSERT INTO rca_event(id, run_id, seq, event_id, event_type, payload,
                    payload_digest, created_at)
                VALUES (:id, :run, :seq, :eid, 'TEST_EVENT', CAST('{}' AS jsonb), :digest,
                    :createdAt::timestamptz)
                """)
                .param("id", UUID.randomUUID()).param("run", run).param("seq", seq)
                .param("eid", UUID.randomUUID())
                .param("digest", Digest.sha256Of(String.valueOf(seq)).value())
                .param("createdAt", createdAt).update();
    }

    private RetentionPolicy policy(long hotDays) {
        return new RetentionPolicy(UUID.randomUUID(), 1, hotDays, "file:///var/archive",
                false, CLOCK);
    }

    @Test
    void itR01_partitionRoutingAndCrossMonthParentQueryUnchanged() {
        UUID run = seedRun();
        seedEvent(run, 1, "2026-07-15 08:00:00+00");   // 2026-09 前边界外 → default 分区
        seedEvent(run, 2, "2026-09-15 08:00:00+00");   // 当前分区 rca_event_2026_09

        // 分区路由各就各位
        List<String> partitions = controlJdbc.sql(
                        "SELECT tableoid::regclass::text FROM rca_event WHERE run_id = :run ORDER BY seq")
                .param("run", run).query(String.class).list();
        assertThat(partitions).containsExactly("rca_event_default", "rca_event_2026_09");

        // 父表跨月查询结果不变（验收面）
        Long total = controlJdbc.sql("SELECT count(*) FROM rca_event WHERE run_id = :run")
                .param("run", run).query(Long.class).single();
        assertThat(total).isEqualTo(2);

        // 分区表注册面
        String strategy = controlJdbc.sql(
                        "SELECT partstrat FROM pg_partitioned_table"
                                + " WHERE partrelid = 'rca_event'::regclass")
                .query(String.class).single();
        assertThat(strategy).isEqualTo("r");
    }

    @Test
    void itR02_uniqueWideningAllowsAcrossMonthsStillRejectsSameMonthDuplicate() {
        UUID run = seedRun();
        seedEvent(run, 1, "2026-07-15 08:00:00+00");
        // E-17 放宽面：跨月同 (run_id, seq) 可容（分区键并入唯一约束的代价；
        // 无洞单调由 M4-10 计数器行锁在应用面保证）
        seedEvent(run, 1, "2026-09-15 08:00:00+00");
        assertThat(count("rca_event")).isEqualTo(2);

        // 同月重复（同 (run_id, seq, created_at)）仍被拒
        assertThatThrownBy(() -> seedEvent(run, 1, "2026-09-15 08:00:00+00"))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("rca_event")).isEqualTo(2);
    }

    @Test
    void itR03_policyChainInsertOnlyAndLatestWins() {
        policies.insertPolicy(policy(1));
        policies.insertPolicy(new RetentionPolicy(UUID.randomUUID(), 2, 90,
                "file:///cold-v2", false, CLOCK));

        assertThat(policies.latestPolicy()).hasValueSatisfying(p -> {
            assertThat(p.policyVersion()).isEqualTo(2);
            assertThat(p.coldLocation()).isEqualTo("file:///cold-v2");
        });

        // insert-only 授权面：应用角色 UPDATE 被拒（历史不可覆盖，INV-AM5-1 同构）
        assertThatThrownBy(() -> controlJdbc.sql(
                        "UPDATE retention_policy SET hot_retention_days = 7")
                .update())
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void itR04_legalHoldBlocksAllCandidatesUntilReleased() {
        policies.insertPolicy(policy(1));
        // V28 新库分区面 = default + 2026_09 + 2026_10；CLOCK=2027-01 → 09/10 均过期
        assertThat(service.archiveCandidates("rca_event"))
                .containsExactly("rca_event_2026_09", "rca_event_2026_10");

        // 族域 hold → INV-AM5-9：候选清零
        UUID hold = policies.insertHold("rca_event", "司法取证", "ops-1", CLOCK);
        assertThat(service.cleanupBlocked("rca_event")).isTrue();
        assertThat(service.archiveCandidates("rca_event")).isEmpty();

        // 分区域 hold 只钉一区
        UUID hold2 = policies.insertHold("rca_event:rca_event_2026_09", "个案",
                "ops-1", CLOCK);
        assertThat(service.archiveCandidates("rca_event"))
                .containsExactly("rca_event_2026_10");

        // release 单列开口：解除后候选恢复；二次释放 0 行 = false
        assertThat(policies.releaseHold(hold, CLOCK.plusSeconds(60))).isTrue();
        assertThat(policies.releaseHold(hold, CLOCK.plusSeconds(60))).isFalse();
        policies.releaseHold(hold2, CLOCK.plusSeconds(60));
        assertThat(policies.activeHolds()).isEmpty();
        assertThat(service.archiveCandidates("rca_event"))
                .containsExactly("rca_event_2026_09", "rca_event_2026_10");

        // delete 零开口（审计面）
        assertThatThrownBy(() -> controlJdbc.sql("DELETE FROM legal_hold").update())
                .isInstanceOf(RuntimeException.class);
    }
}
