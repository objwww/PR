package com.objwww.pr.control.it;

import com.objwww.pr.control.infrastructure.archive.LocalColdArchiveStore;
import com.objwww.pr.control.infrastructure.persistence.PostgresArchiveManifestRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresPartitionArchiveGateway;
import com.objwww.pr.control.infrastructure.persistence.PostgresPartitionCatalog;
import com.objwww.pr.control.infrastructure.persistence.PostgresRetentionPolicyRepository;
import com.objwww.pr.control.ops.application.ArchiveService;
import com.objwww.pr.control.ops.application.RetentionService;
import com.objwww.pr.control.ops.domain.model.RetentionPolicy;
import com.objwww.pr.control.ops.domain.repository.ArchiveManifestRepository;
import com.objwww.pr.control.ops.domain.repository.ColdArchiveStore;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.Digests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5-19 真 PG 冷归档链路（V28 分区 + V29 manifest；方案 §12.1 L2，本机无 Docker
 * 自动跳过）：happy path（导出→digest 对拍→detach keep_table→manifest ARCHIVED、
 * 恰一次栅栏、state 单向）、导出失败零副作用不删热数据、备份损坏被 verify 拦截
 * （manifest 留 EXPORTED 作审计、分区原封）、legal hold 零副作用拒绝（INV-AM5-9）、
 * 无策略 fail-closed。
 */
class PostgresArchiveIT extends PostgresITBase {

    private static final Instant CLOCK = Instant.parse("2027-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    private PostgresRetentionPolicyRepository policies;
    private RetentionService retention;
    private PostgresArchiveManifestRepository manifests;
    private LocalColdArchiveStore coldStore;
    private ArchiveService service;

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
        retention = new RetentionService(policies, new PostgresPartitionCatalog(controlJdbc),
                () -> CLOCK);
        manifests = new PostgresArchiveManifestRepository(controlJdbc);
        coldStore = new LocalColdArchiveStore(tempDir);
        service = new ArchiveService(policies, retention,
                new PostgresPartitionArchiveGateway(new JdbcTemplate(controlDataSource())),
                coldStore, manifests);
    }

    /** itA01 摘离后恢复 V28 分区注册面（下一测试的 TRUNCATE/种子依赖完整布局） */
    @AfterEach
    void reattachDetachedPartitions() {
        String[][] bounds = {
                {"rca_event_2026_09", "'2026-09-01 00:00:00+00'", "'2026-10-01 00:00:00+00'"},
                {"rca_event_2026_10", "'2026-10-01 00:00:00+00'", "'2026-11-01 00:00:00+00'"},
        };
        for (String[] bound : bounds) {
            boolean exists = adminJdbc.sql("SELECT to_regclass(:n) IS NOT NULL")
                    .param("n", "public." + bound[0]).query(Boolean.class).single();
            boolean attached = adminJdbc.sql("""
                            SELECT count(*) > 0 FROM pg_inherits i
                              JOIN pg_class c ON c.oid = i.inhrelid
                             WHERE c.relname = :name
                            """).param("name", bound[0])
                    .query(Boolean.class).single();
            if (exists && !attached) {
                adminJdbc.sql("ALTER TABLE rca_event ATTACH PARTITION " + bound[0]
                        + " FOR VALUES FROM (" + bound[1] + ") TO (" + bound[2] + ")").update();
            }
        }
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

    private void seedOctoberPartition() {
        UUID run = seedRun();
        seedEvent(run, 1, "2026-10-15 08:00:00+00");
        seedEvent(run, 2, "2026-10-16 08:00:00+00");
    }

    private RetentionPolicy policy() {
        return new RetentionPolicy(UUID.randomUUID(), 1, 30,
                "file://" + tempDir.toAbsolutePath().normalize(), false, CLOCK);
    }

    /** 分区当前是否仍挂在 rca_event 注册面（pg_inherits） */
    private boolean attached(String partition) {
        return adminJdbc.sql("""
                        SELECT count(*) > 0 FROM pg_inherits i
                          JOIN pg_class c ON c.oid = i.inhrelid
                         WHERE c.relname = :name
                        """).param("name", partition)
                .query(Boolean.class).single();
    }

    @Test
    void itA01_happyPathDetachesKeepTableAndManifestAdvancesOneWay() {
        seedOctoberPartition();
        policies.insertPolicy(policy());

        ArchiveService.Result result = service.archive("rca_event", "rca_event_2026_10");
        assertThat(result.outcome()).isEqualTo(ArchiveService.Outcome.ARCHIVED);

        // manifest 终态：行数/摘要/冷层引用可回读对拍
        ArchiveManifestRepository.Row row =
                manifests.findByPartition("rca_event_2026_10").orElseThrow();
        assertThat(row.state()).isEqualTo("ARCHIVED");
        assertThat(row.rowCount()).isEqualTo(2);
        assertThat(Digests.sha256Hex(coldStore.readBack(row.exportRef()))).isEqualTo(row.digest());

        // keep_table：表仍在、行原封，但已离开父表注册面（父表查询不再可见）
        assertThat(attached("rca_event_2026_10")).isFalse();
        assertThat(count("rca_event_2026_10")).isEqualTo(2);
        assertThat(count("rca_event")).isZero();

        // 恰一次栅栏 + state 单向（ARCHIVED 不可倒退）
        assertThat(service.archive("rca_event", "rca_event_2026_10").outcome())
                .isEqualTo(ArchiveService.Outcome.ALREADY_ARCHIVED);
        assertThat(manifests.advanceState("rca_event_2026_10", "ARCHIVED", "EXPORTED")).isFalse();
    }

    @Test
    void itA02_exportFailureKeepsPartitionAttachedWithZeroManifest() {
        seedOctoberPartition();
        policies.insertPolicy(policy());
        ArchiveService unavailable = new ArchiveService(policies, retention,
                new PostgresPartitionArchiveGateway(new JdbcTemplate(controlDataSource())),
                new ColdArchiveStore() {
                    @Override
                    public String export(String partition, byte[] content) {
                        throw new IllegalStateException("冷层不可达");
                    }

                    @Override
                    public byte[] readBack(String ref) {
                        throw new IllegalStateException("冷层不可达");
                    }
                }, manifests);

        ArchiveService.Result result =
                unavailable.archive("rca_event", "rca_event_2026_10");

        assertThat(result.outcome()).isEqualTo(ArchiveService.Outcome.FAILED_EXPORT);
        assertThat(count("archive_manifest")).isZero();
        assertThat(attached("rca_event_2026_10")).isTrue();
        assertThat(count("rca_event")).isEqualTo(2);
    }

    @Test
    void itA03_corruptedBackupStopsAtVerifyWithManifestPinnedExported() {
        seedOctoberPartition();
        policies.insertPolicy(policy());
        ArchiveService corrupting = new ArchiveService(policies, retention,
                new PostgresPartitionArchiveGateway(new JdbcTemplate(controlDataSource())),
                new ColdArchiveStore() {
                    @Override
                    public String export(String partition, byte[] content) {
                        return coldStore.export(partition, content);
                    }

                    @Override
                    public byte[] readBack(String ref) {
                        byte[] data = coldStore.readBack(ref);
                        data[0] ^= 0xFF; // 单字节翻转 = digest 失配
                        return data;
                    }
                }, manifests);

        ArchiveService.Result result =
                corrupting.archive("rca_event", "rca_event_2026_10");

        assertThat(result.outcome()).isEqualTo(ArchiveService.Outcome.FAILED_VERIFY);
        // 审计面：导出过但未被信任——manifest 留在 EXPORTED，分区原封
        assertThat(manifests.findByPartition("rca_event_2026_10"))
                .hasValueSatisfying(row -> assertThat(row.state()).isEqualTo("EXPORTED"));
        assertThat(attached("rca_event_2026_10")).isTrue();
        assertThat(count("rca_event")).isEqualTo(2);
    }

    @Test
    void itA04_legalHoldRejectsWithZeroSideEffectsFamilyAndPartitionScope() {
        seedOctoberPartition();
        policies.insertPolicy(policy());

        // 族域 hold
        UUID family = policies.insertHold("rca_event", "司法取证", "ops-1", CLOCK);
        assertThat(service.archive("rca_event", "rca_event_2026_10").outcome())
                .isEqualTo(ArchiveService.Outcome.REJECTED_HOLD);
        assertThat(count("archive_manifest")).isZero();
        assertThat(attached("rca_event_2026_10")).isTrue();
        assertThat(count("rca_event")).isEqualTo(2);
        policies.releaseHold(family, CLOCK.plusSeconds(60));

        // 分区域 hold 同样阻断
        policies.insertHold("rca_event:rca_event_2026_10", "个案取证", "ops-1", CLOCK);
        assertThat(service.archive("rca_event", "rca_event_2026_10").outcome())
                .isEqualTo(ArchiveService.Outcome.REJECTED_HOLD);
        assertThat(count("archive_manifest")).isZero();
        assertThat(attached("rca_event_2026_10")).isTrue();
    }

    @Test
    void itA05_noPolicyMeansFailClosedWithZeroSideEffects() {
        seedOctoberPartition();

        ArchiveService.Result result = service.archive("rca_event", "rca_event_2026_10");

        assertThat(result.outcome()).isEqualTo(ArchiveService.Outcome.NO_POLICY);
        assertThat(count("archive_manifest")).isZero();
        assertThat(attached("rca_event_2026_10")).isTrue();
        assertThat(count("rca_event")).isEqualTo(2);
    }
}
