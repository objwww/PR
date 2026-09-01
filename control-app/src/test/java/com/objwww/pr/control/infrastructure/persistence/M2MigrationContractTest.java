package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.domain.model.RunMode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2-T01 本地静态门：Docker 不可用时也锁住 V4 的关键结构与权限边界。
 * 真 PostgreSQL 约束/授权行为由 CT-22 起的集成测试覆盖。
 */
class M2MigrationContractTest {

    private static final Path V4 = Path.of(
            "src/main/resources/db/migration/V4__m2_checkpoint_repair.sql");

    @Test
    void v4ContainsCheckpointRepairAndLeastPrivilegeBoundaries() throws IOException {
        // 规范化大小写/空白后整体比对：断言锚定 DDL 形状而非易撞串的散子串（RM2-12）
        String sql = Files.readString(V4).toLowerCase().replaceAll("\\s+", " ");

        assertThat(sql)
                .contains("create table step_checkpoint")
                .contains("checkpoint_contract_digest")
                .contains("prompt_template_version")
                .contains("finding_schema_version")
                .contains("mapper_contract_version")
                .contains("context_builder_version")
                .contains("model_identity")
                .contains("lease_epoch")
                .contains("constraint uq_step_checkpoint unique (step_id, checkpoint_key)")
                .contains("create table repair_request")
                // 锚定 uq_repair_active 索引名 + 部分谓词整体，不与 ck_repair_state 撞串（RM2-12）
                .contains("create unique index uq_repair_active on repair_request(publication_resource_id)"
                        + " where state in ('pending', 'approved', 'dispatched', 'retry_wait')")
                .contains("'repaired', 'failed_terminal', 'expired'")
                .contains("replaces_resource_id")
                .contains("content_drift_detected_at")
                .contains("'isolated_reexecution', 'repair'")
                .contains("revoke all privileges on step_checkpoint from publisher_app")
                .contains("revoke insert, delete on outbox_command from publisher_app");
    }

    @Test
    void publisherCannotForgeApprovalOnRepairRequest() throws IOException {
        // RM2-02：publisher 只持列级 INSERT（业务列）；整行 INSERT 授权与 INSERT trigger 双双钉住
        String sql = Files.readString(V4).toLowerCase().replaceAll("\\s+", " ");

        assertThat(sql)
                .contains("grant insert (id, publication_resource_id, resource_type, policy_tier, state,"
                        + " attempt_count, max_attempts, next_attempt_at, last_error,"
                        + " created_at, updated_at) on repair_request to publisher_app")
                .contains("grant update (state, repair_run_id, repair_operation_id, attempt_count,"
                        + " next_attempt_at, last_error, updated_at) on repair_request to publisher_app")
                .contains("create trigger trg_repair_insert_pending before insert on repair_request")
                .contains("new.state <> 'pending'")
                .contains("new.approved_by is not null")
                // 整行 INSERT（不带列清单）授权必须不存在（trigger DDL 的 before insert 不算授权）
                .doesNotContainPattern("grant [a-z ,]*insert on repair_request");
    }

    @Test
    void repairRunMayEnablePublishing() throws IOException {
        // RM2-10：旧 CHECK 废弃；回放/重建类禁发布不变，REPAIR 允许 publisher_disabled=false
        String sql = Files.readString(V4).toLowerCase().replaceAll("\\s+", " ");

        assertThat(sql)
                .contains("alter table review_run drop constraint ck_replay_publisher_disabled")
                .contains("constraint ck_replay_publisher_disabled check"
                        + " (run_mode in ('normal', 'repair') or publisher_disabled = true)");
    }

    @Test
    void repairRunModeIsWiredToDomainEnum() {
        assertThat(RunMode.valueOf("REPAIR")).isEqualTo(RunMode.REPAIR);
    }
}
