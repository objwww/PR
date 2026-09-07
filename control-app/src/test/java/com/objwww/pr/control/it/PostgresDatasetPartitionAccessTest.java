package com.objwww.pr.control.it;

import com.objwww.pr.shared.Digest;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V21 四分区权限真 PG 组件测试（M5-02；方案 §12.1 L2 的 IT 面）：
 * Agent/RAG 身份（control_app 族）对 case_version 查询恒 0（零 grant + RLS 零策略
 * 双保险）、eval_app（SCORING）封存前不见 HOLDOUT、PUBLIC_BENCHMARK 数据行不得
 * 落 HOLDOUT（INV-AM5-1 触发器兜底）、同 family 拆跨分区被登记面拒绝、
 * 四分区角色各见单分区（RLS 策略行为面）。
 * 本机无 Docker 自动跳过（真证据待 195 释放后统一补）。
 */
class PostgresDatasetPartitionAccessTest extends PostgresITBase {

    private JdbcClient partitionProbeJdbc;
    private TransactionTemplate partitionProbeTx;

    @BeforeEach
    void setUp() {
        adminJdbc.sql("DELETE FROM case_version").update();
        adminJdbc.sql("DELETE FROM case_family_partition").update();
        adminJdbc.sql("DELETE FROM dataset_version").update();

        // 单连接探针：SET LOCAL ROLE 与计数须同连接同事务（SET LOCAL 随事务结束还原，
        // 不污染连接池）
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(PG.getJdbcUrl());
        cfg.setUsername(PG.getUsername());
        cfg.setPassword(PG.getPassword());
        cfg.setMaximumPoolSize(1);
        HikariDataSource ds = new HikariDataSource(cfg);
        partitionProbeJdbc = JdbcClient.create(ds);
        partitionProbeTx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    /** admin 直插数据集版本头（owner 绕开授权面；INV-AM5-1 触发器照常生效） */
    private UUID seedDataset(String name, String version, String sourceClass, String partition) {
        UUID id = UUID.randomUUID();
        adminJdbc.sql("""
                INSERT INTO dataset_version (
                    id, source, name, version, source_uri, license, access_class,
                    content_digest, adapter_version, imported_at, source_class,
                    partition_class, scenario_family_digest
                ) VALUES (:id, 'order-arena', :name, :version, 'https://arena.internal/ds',
                    'internal', 'internal-use', :digest, 'oa-v1', now(),
                    :sourceClass, :partition, :digest)
                """)
                .param("id", id).param("name", name).param("version", version)
                .param("digest", Digest.sha256Of(name + "-" + version).value())
                .param("sourceClass", sourceClass).param("partition", partition)
                .update();
        return id;
    }

    /** admin 直插案例行（含分区冗余列） */
    private void seedCase(UUID datasetId, String caseKey, String family, String partition,
                          Instant validFrom, Instant validTo) {
        adminJdbc.sql("""
                INSERT INTO case_version (
                    id, dataset_version_id, case_key, scenario_family_id, partition_class,
                    valid_from, valid_to, content_digest, payload, source_artifact_ref
                ) VALUES (:id, :ds, :caseKey, :family, :partition,
                    :from, :to, :digest, '{}'::jsonb, null)
                """)
                .param("id", UUID.randomUUID()).param("ds", datasetId)
                .param("caseKey", caseKey).param("family", family)
                .param("partition", partition).param("from", java.sql.Timestamp.from(validFrom))
                .param("to", validTo == null ? null : java.sql.Timestamp.from(validTo))
                .param("digest", Digest.sha256Of(caseKey).value())
                .update();
    }

    /** 以指定分区角色视角计数（SET LOCAL ROLE 事务内生效、提交即还原） */
    private long countAsRole(String role, String partitionFilter) {
        Long n = partitionProbeTx.execute(status -> {
            partitionProbeJdbc.sql("set local role " + role).update();
            return partitionProbeJdbc.sql(
                            "select count(*) from case_version where " + partitionFilter)
                    .query(Long.class).single();
        });
        return n == null ? -1 : n;
    }

    @Test
    void controlAppIdentityHasZeroAccessToCaseVersion() {
        UUID ds = seedDataset("fault-injection", "v1", "PRIVATE", "TUNING");
        seedCase(ds, "case-1", "family-f1", "TUNING", Instant.parse("2026-09-01T00:00:00Z"), null);

        // Agent/RAG/调优界面身份（control_app 族）：零 grant（V20 revoke）+ RLS 零策略
        // 双保险——查询在授权层直接拒绝（42501），不存在"侥幸可见"
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> controlJdbc.sql("select count(*) from case_version")
                        .query(Long.class).single());
    }

    @Test
    void evalAppSeesNonHoldoutRowsOnlyUntilReportSealed() {
        UUID tuning = seedDataset("fault-injection", "v1", "PRIVATE", "TUNING");
        UUID holdout = seedDataset("fault-injection", "v2", "PRIVATE", "HOLDOUT");
        seedCase(tuning, "case-t1", "family-f1", "TUNING",
                Instant.parse("2026-09-01T00:00:00Z"), null);
        seedCase(holdout, "case-h1", "family-f2", "HOLDOUT",
                Instant.parse("2026-09-01T00:00:00Z"), null);

        // SCORING（eval_app）：TUNING 可见，HOLDOUT 封存门前恒 0（RLS select 策略）
        assertThat(evalJdbc.sql("select count(*) from case_version").query(Long.class).single())
                .as("eval_app 只见非 HOLDOUT 行").isEqualTo(1L);
        assertThat(evalJdbc.sql("select case_key from case_version").query(String.class).single())
                .isEqualTo("case-t1");

        // RLS insert with check：eval_app 写 HOLDOUT 行被策略拒绝（即使复合 FK 合法）
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> seedCaseAsEvalApp(holdout, "case-h2", "family-f2",
                        Instant.parse("2026-09-01T00:00:00Z")));
        // admin（owner）视角全貌 = 2 行：行没被删也没被藏进错误分区
        assertThat(count("case_version")).isEqualTo(2L);
    }

    private void seedCaseAsEvalApp(UUID datasetId, String caseKey, String family, Instant from) {
        evalJdbc.sql("""
                INSERT INTO case_version (
                    id, dataset_version_id, case_key, scenario_family_id, partition_class,
                    valid_from, valid_to, content_digest, payload, source_artifact_ref
                ) SELECT :id, :ds, :caseKey, :family, dv.partition_class,
                    :from, null, :digest, '{}'::jsonb, null
                FROM dataset_version dv WHERE dv.id = :ds
                """)
                .param("id", UUID.randomUUID()).param("ds", datasetId)
                .param("caseKey", caseKey).param("family", family)
                .param("from", java.sql.Timestamp.from(from))
                .param("digest", Digest.sha256Of(caseKey).value())
                .update();
    }

    @Test
    void partitionRolesSeeExactlyTheirOwnPartition() {
        UUID tuning = seedDataset("d-t", "v1", "PRIVATE", "TUNING");
        UUID validation = seedDataset("d-v", "v1", "PRIVATE", "VALIDATION");
        UUID holdout = seedDataset("d-h", "v1", "PRIVATE", "HOLDOUT");
        UUID redteam = seedDataset("d-r", "v1", "PRIVATE", "REDTEAM");
        seedCase(tuning, "case-t", "family-t", "TUNING",
                Instant.parse("2026-09-01T00:00:00Z"), null);
        seedCase(validation, "case-v", "family-v", "VALIDATION",
                Instant.parse("2026-09-01T00:00:00Z"), null);
        seedCase(holdout, "case-h", "family-h", "HOLDOUT",
                Instant.parse("2026-09-01T00:00:00Z"), null);
        seedCase(redteam, "case-r", "family-r", "REDTEAM",
                Instant.parse("2026-09-01T00:00:00Z"), null);

        assertThat(countAsRole("eval_tuning_ro", "partition_class = 'TUNING'"))
                .as("调优只读角色见 TUNING 单分区").isEqualTo(1L);
        assertThat(countAsRole("eval_validation_ro", "partition_class = 'VALIDATION'"))
                .isEqualTo(1L);
        assertThat(countAsRole("eval_holdout_gate", "partition_class = 'HOLDOUT'"))
                .as("封存门角色见 HOLDOUT 单分区").isEqualTo(1L);
        assertThat(countAsRole("eval_redteam_gate", "partition_class = 'REDTEAM'"))
                .isEqualTo(1L);
        // 越分区查询恒 0（策略谓词过滤，不是报错）
        assertThat(countAsRole("eval_tuning_ro", "partition_class = 'HOLDOUT'"))
                .as("调优角色对 HOLDOUT 恒 0").isZero();
        assertThat(countAsRole("eval_holdout_gate", "partition_class = 'TUNING'"))
                .as("封存门角色对 TUNING 恒 0").isZero();
    }

    @Test
    void publicBenchmarkDatasetCannotClaimHoldoutPartition() {
        // INV-AM5-1 触发器兜底：PUBLIC_BENCHMARK × HOLDOUT 直接拒绝
        assertThatThrownBy(() -> seedDataset("rcabench", "v1.1", "PUBLIC_BENCHMARK", "HOLDOUT"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("INV-AM5-1");
        // 非 HOLDOUT 分区不受影响
        seedDataset("rcabench", "v1.1", "PUBLIC_BENCHMARK", "VALIDATION");
        assertThat(count("dataset_version")).isEqualTo(1L);
    }

    @Test
    void familySecondRegistrationInDifferentPartitionRejected() {
        // 登记面（insert-only）：同 family 二次登记不同分区 = DuplicateKey 拒绝
        evalJdbc.sql("insert into case_family_partition(scenario_family_id, partition_class) "
                        + "values (:f, :p)").param("f", "family-f1").param("p", "TUNING").update();
        assertThatThrownBy(() -> evalJdbc.sql(
                        "insert into case_family_partition(scenario_family_id, partition_class) "
                                + "values (:f, :p)")
                .param("f", "family-f1").param("p", "HOLDOUT").update())
                .isInstanceOf(DuplicateKeyException.class)
                .as("同 family 拆跨分区 = 违约（落码方案 §M5-02② DB 兜底，C-6 登记面形态）");
        // 同分区重申：PK 冲突同样拒绝（重申幂等由导入 runner 走 ON CONFLICT DO NOTHING）
        assertThatThrownBy(() -> evalJdbc.sql(
                        "insert into case_family_partition(scenario_family_id, partition_class) "
                                + "values (:f, :p)")
                .param("f", "family-f1").param("p", "TUNING").update())
                .isInstanceOf(DuplicateKeyException.class);
        // 新族新分区正常登记
        evalJdbc.sql("insert into case_family_partition(scenario_family_id, partition_class) "
                        + "values (:f, :p)").param("f", "family-f2").param("p", "REDTEAM").update();
        assertThat(count("case_family_partition")).isEqualTo(2L);
    }
}
