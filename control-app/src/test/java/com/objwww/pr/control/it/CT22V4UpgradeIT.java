package com.objwww.pr.control.it;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * CT-22（docs/M2-技术方案.md §11 L2 表，回指评审 #17 / §4.1 V4 DDL）。
 *
 * <p>场景：带历史数据原地升级——在独立库（同容器，不影响共享主库）按 V1→V3 迁移，
 * 灌 V3 形态历史数据（Run/Outbox/PRESENT/MISSING 资源行），再 Flyway 全链升 V4；
 * 最后注入一条坏迁移验证 flyway 阻断启动。
 *
 * <p>断言：升级后数据零丢失（五表计数不变）、旧记录逐字段可读、V4 新列默认 NULL；
 * 权限不放宽——publisher 仍零 outbox INSERT（42501）、repair_request 列级 INSERT 生效
 * （越权列写 42501；合列但 state='APPROVED' 被 trg_repair_insert_pending 以 P0001 拒绝）；
 * 坏迁移使 migrate() 抛 FlywayException 且成功历史停在 V4。
 *
 * <p>取证：flyway_schema_history.version/success；pr_subject/pr_revision/review_run/
 * outbox_command/publication_resource 行计数与字段值；publication_resource 的
 * replaces_resource_id 等 V4 新列；repair_request 铸单结果。
 */
class CT22V4UpgradeIT extends PostgresITBase {

    private static final String DB_NAME = "ct22_upgrade";

    @Test
    void v3DataSurvivesV4UpgradeAndPermissionsStayTight() throws Exception {
        adminJdbc.sql("DROP DATABASE IF EXISTS " + DB_NAME + " WITH (FORCE)").update();
        adminJdbc.sql("CREATE DATABASE " + DB_NAME).update();
        String url = "jdbc:postgresql://" + PG.getHost() + ":" + PG.getFirstMappedPort() + "/" + DB_NAME;

        try (HikariDataSource admin2 = pool(url, PG.getUsername(), PG.getPassword());
             HikariDataSource publisher2 = pool(url, PUBLISHER_ROLE, PUBLISHER_PASSWORD)) {
            JdbcClient admin2Jdbc = JdbcClient.create(admin2);
            JdbcClient publisher2Jdbc = JdbcClient.create(publisher2);

            // ---- 阶段一：只迁到 V3，灌 V3 形态历史数据 ----
            Flyway.configure().dataSource(admin2).locations("classpath:db/migration")
                    .target("3").load().migrate();
            assertThat(maxSuccessMigration(admin2Jdbc)).isEqualTo("3");

            RepairSeed s1 = seedRepairScope(admin2Jdbc, "ct22-a");
            UUID op1 = seedConfirmedCommand(admin2Jdbc, s1, "CREATE_CHECK", "{\"name\":\"ai-review\"}");
            UUID present = seedResource(admin2Jdbc, s1, op1, "CHECK_RUN", "PRESENT", "ct22-present");
            RepairSeed s2 = seedRepairScope(admin2Jdbc, "ct22-b");
            UUID op2 = seedConfirmedCommand(admin2Jdbc, s2, "PUBLISH_REVIEW", "{\"body\":\"r\"}");
            UUID missing = seedResource(admin2Jdbc, s2, op2, "REVIEW", "MISSING", "ct22-missing");

            // ---- 阶段二：全链升 V4 ----
            Flyway.configure().dataSource(admin2).locations("classpath:db/migration")
                    .load().migrate();
            assertThat(maxSuccessMigration(admin2Jdbc)).isEqualTo("4");

            // 数据零丢失
            for (String table : new String[]{"pr_subject", "pr_revision", "review_run",
                    "outbox_command", "publication_resource"}) {
                assertThat(countIn(admin2Jdbc, table)).as(table).isEqualTo(2);
            }
            // 旧记录逐字段可读
            assertThat(admin2Jdbc.sql("SELECT state FROM publication_resource WHERE id=:id")
                    .param("id", present).query(String.class).single()).isEqualTo("PRESENT");
            assertThat(admin2Jdbc.sql("SELECT state FROM publication_resource WHERE id=:id")
                    .param("id", missing).query(String.class).single()).isEqualTo("MISSING");
            assertThat(admin2Jdbc.sql(
                    "SELECT trim(payload_hash) FROM outbox_command WHERE operation_id=:id")
                    .param("id", op1).query(String.class).single())
                    .isEqualTo(com.objwww.pr.shared.Digest.sha256Of("{\"name\":\"ai-review\"}").value());
            // V4 新列默认 NULL
            assertThat(admin2Jdbc.sql(
                    "SELECT count(*) FROM publication_resource WHERE replaces_resource_id IS NULL"
                            + " AND content_drift_detected_at IS NULL AND content_drift_digest IS NULL")
                    .query(Long.class).single()).isEqualTo(2);

            // ---- 阶段三：权限不放宽（升级后 publisher 视角）----
            // publisher 仍零 outbox INSERT（AFT-14 回归）
            assertSqlState("42501", () -> publisher2Jdbc.sql("""
                    INSERT INTO outbox_command(operation_id,pr_subject_id,review_run_id,pr_revision_id,
                        aggregate_key,aggregate_sequence,publication_epoch,fence_mode,command_type,state,
                        policy_version,payload_hash,remote_identity_type,attempt_count,max_attempts,
                        created_at,updated_at)
                    VALUES (:op,:s,:run,:rev,'agg',9,1,'CURRENT_EPOCH','CREATE_CHECK','PENDING',
                        'p',:hash,'EXTERNAL_ID',0,3,now(),now())
                    """).param("op", UUID.randomUUID()).param("s", s1.subjectId())
                    .param("run", s1.runId()).param("rev", s1.revisionId())
                    .param("hash", "b".repeat(64)).update());
            // publisher 对 step_checkpoint 零权限（AFT-18）
            assertSqlState("42501", () -> publisher2Jdbc.sql("""
                    INSERT INTO step_checkpoint(id,step_id,checkpoint_key,output_artifact_digest,
                        model_response_digest,checkpoint_contract_digest,prompt_template_version,
                        finding_schema_version,mapper_contract_version,context_builder_version,
                        model_identity,lease_epoch,attempt_no)
                    VALUES (:id,:step,'K',:d,:d,:d,'p','s','m','c','m',1,1)
                    """).param("id", UUID.randomUUID()).param("step", UUID.randomUUID())
                    .param("d", "c".repeat(64)).update());

            // repair_request 列级 INSERT：合列 + PENDING → 铸单成功（DriftReconciler 路径）
            UUID legit = UUID.randomUUID();
            publisher2Jdbc.sql("""
                    INSERT INTO repair_request(id,publication_resource_id,resource_type,policy_tier,state)
                    VALUES (:id,:rid,'CHECK_RUN','AUTO','PENDING')
                    """).param("id", legit).param("rid", missing).update();
            assertThat(admin2Jdbc.sql("SELECT state FROM repair_request WHERE id=:id")
                    .param("id", legit).query(String.class).single()).isEqualTo("PENDING");

            // 越权列（approved_by 不在 publisher 的列级 INSERT 授权内）→ 42501
            assertSqlState("42501", () -> publisher2Jdbc.sql("""
                    INSERT INTO repair_request(id,publication_resource_id,resource_type,policy_tier,state,
                        approved_by)
                    VALUES (:id,:rid,'CHECK_RUN','AUTO','PENDING','forged')
                    """).param("id", UUID.randomUUID()).param("rid", missing).update());
            // 合列但伪造 APPROVED 出生 → trg_repair_insert_pending 拒绝（RM2-02，P0001）
            UUID forged = UUID.randomUUID();
            Throwable t = catchThrowable(() -> publisher2Jdbc.sql("""
                    INSERT INTO repair_request(id,publication_resource_id,resource_type,policy_tier,state)
                    VALUES (:id,:rid,'CHECK_RUN','AUTO','APPROVED')
                    """).param("id", forged).param("rid", missing).update());
            assertThat(sqlStateOf(t)).isEqualTo("P0001");
            assertThat(t).hasStackTraceContaining("PENDING");
            assertThat(countIn(admin2Jdbc, "repair_request")).isEqualTo(1); // 只有合法那张单

            // ---- 阶段四：注入坏迁移 → flyway 阻断启动 ----
            Path badDir = Files.createTempDirectory("ct22-bad-migration");
            Files.writeString(badDir.resolve("V5__bad.sql"), "this is not valid sql;");
            Throwable blocked = catchThrowable(() -> Flyway.configure().dataSource(admin2)
                    // Windows 反斜杠路径统一转 /，Flyway filesystem location 只认正斜杠
                    .locations("classpath:db/migration",
                            "filesystem:" + badDir.toString().replace('\\', '/'))
                    .load().migrate());
            assertThat(blocked).isInstanceOf(FlywayException.class);
            assertThat(maxSuccessMigration(admin2Jdbc)).isEqualTo("4"); // 成功历史停在 V4
        }
    }

    private static HikariDataSource pool(String url, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        return new HikariDataSource(config);
    }

    private static long countIn(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static String maxSuccessMigration(JdbcClient jdbc) {
        return jdbc.sql("SELECT max(version) FROM flyway_schema_history WHERE success")
                .query(String.class).single();
    }

    private static void assertSqlState(String expected, ThrowingCallable call) {
        assertThat(sqlStateOf(catchThrowable(call))).isEqualTo(expected);
    }

    private static String sqlStateOf(Throwable t) {
        assertThat(t).as("预期抛出 SQLException").isNotNull();
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(PSQLException.class);
        return ((PSQLException) root).getSQLState();
    }
}
