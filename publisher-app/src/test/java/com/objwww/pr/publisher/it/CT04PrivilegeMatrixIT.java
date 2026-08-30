package com.objwww.pr.publisher.it;

import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CT-04 DB 角色权限矩阵（AFT-06 动态部分）：control INSERT outbox 成功、UPDATE 被拒；
 * publisher 反之；has_table_privilege 断言全矩阵。
 *
 * <p>本用例同时是 V2 授权文件的忠实刻画。T17 曾发现 publisher_app 对 pr_subject
 * 仅 SELECT 与评审修正 #5（publisher 在 T3-B 同事务推进 last_resolved_sequence）
 * 冲突——已以列级授权修复（publisher 只能 UPDATE last_resolved_sequence/updated_at
 * 两列，见 V2 注释）；下方矩阵与负探针刻画修复后的边界。
 */
class CT04PrivilegeMatrixIT extends PostgresITBase {

    private ItHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ItHarness(casDir, null);
    }

    private boolean hasPrivilege(String role, String table, String privilege) {
        return adminJdbc.sql("SELECT has_table_privilege(:role, :table, :priv)")
                .param("role", role).param("table", table).param("priv", privilege)
                .query(Boolean.class).single();
    }

    @Test
    void controlRoleMatrix() {
        // control_app：写路径全通，outbox 只 INSERT 不 UPDATE/DELETE（AFT-06）
        assertThat(hasPrivilege(CONTROL_ROLE, "pr_subject", "SELECT,INSERT,UPDATE")).isTrue();
        assertThat(hasPrivilege(CONTROL_ROLE, "pr_revision", "SELECT,INSERT")).isTrue();
        assertThat(hasPrivilege(CONTROL_ROLE, "pr_revision", "UPDATE")).isFalse();
        assertThat(hasPrivilege(CONTROL_ROLE, "review_run", "SELECT,INSERT,UPDATE")).isTrue();
        assertThat(hasPrivilege(CONTROL_ROLE, "run_step", "SELECT,INSERT,UPDATE")).isTrue();
        assertThat(hasPrivilege(CONTROL_ROLE, "work_item", "SELECT,INSERT,UPDATE")).isTrue();
        assertThat(hasPrivilege(CONTROL_ROLE, "step_attempt", "SELECT,INSERT,UPDATE")).isTrue();
        assertThat(hasPrivilege(CONTROL_ROLE, "execution_event", "SELECT,INSERT")).isTrue();
        assertThat(hasPrivilege(CONTROL_ROLE, "execution_event", "UPDATE,DELETE")).isFalse();
        assertThat(hasPrivilege(CONTROL_ROLE, "review_finding", "SELECT,INSERT,UPDATE")).isTrue();
        assertThat(hasPrivilege(CONTROL_ROLE, "artifact", "SELECT,INSERT")).isTrue();
        assertThat(hasPrivilege(CONTROL_ROLE, "outbox_command", "SELECT,INSERT")).isTrue();
        assertThat(hasPrivilege(CONTROL_ROLE, "outbox_command", "UPDATE")).isFalse();
        assertThat(hasPrivilege(CONTROL_ROLE, "outbox_command", "DELETE")).isFalse();
        assertThat(hasPrivilege(CONTROL_ROLE, "outbox_dependency", "SELECT,INSERT")).isTrue();
        assertThat(hasPrivilege(CONTROL_ROLE, "publication_resource", "SELECT")).isTrue();
        assertThat(hasPrivilege(CONTROL_ROLE, "publication_resource", "INSERT")).isFalse();
    }

    @Test
    void publisherRoleMatrix() {
        // publisher_app：outbox 只 SELECT/UPDATE（不能伪造写意图），事件只追加
        assertThat(hasPrivilege(PUBLISHER_ROLE, "outbox_command", "SELECT,UPDATE")).isTrue();
        assertThat(hasPrivilege(PUBLISHER_ROLE, "outbox_command", "INSERT")).isFalse();
        assertThat(hasPrivilege(PUBLISHER_ROLE, "outbox_command", "DELETE")).isFalse();
        assertThat(hasPrivilege(PUBLISHER_ROLE, "outbox_dependency", "SELECT")).isTrue();
        assertThat(hasPrivilege(PUBLISHER_ROLE, "execution_event", "SELECT,INSERT")).isTrue();
        assertThat(hasPrivilege(PUBLISHER_ROLE, "publication_resource", "SELECT,INSERT,UPDATE")).isTrue();
        assertThat(hasPrivilege(PUBLISHER_ROLE, "review_run", "SELECT")).isTrue();
        assertThat(hasPrivilege(PUBLISHER_ROLE, "review_finding", "SELECT")).isTrue();
        assertThat(hasPrivilege(PUBLISHER_ROLE, "artifact", "SELECT")).isTrue();
        // pr_subject：SELECT 全表 + 列级 UPDATE 仅游标两列（表级 UPDATE 仍无——
        // has_table_privilege 不计列级授权，实测 PG16；列级断言用 has_column_privilege）
        assertThat(hasPrivilege(PUBLISHER_ROLE, "pr_subject", "SELECT")).isTrue();
        assertThat(hasPrivilege(PUBLISHER_ROLE, "pr_subject", "UPDATE")).isFalse();
        assertThat(hasColumnPrivilege(PUBLISHER_ROLE, "pr_subject",
                "last_resolved_sequence", "UPDATE")).isTrue();
        assertThat(hasColumnPrivilege(PUBLISHER_ROLE, "pr_subject",
                "updated_at", "UPDATE")).isTrue();
        assertThat(hasColumnPrivilege(PUBLISHER_ROLE, "pr_subject",
                "publication_epoch", "UPDATE")).isFalse();
        assertThat(hasColumnPrivilege(PUBLISHER_ROLE, "pr_subject",
                "next_outbox_sequence", "UPDATE")).isFalse();
        assertThat(hasColumnPrivilege(PUBLISHER_ROLE, "pr_subject",
                "current_revision_id", "UPDATE")).isFalse();
    }

    private boolean hasColumnPrivilege(String role, String table, String column, String privilege) {
        return adminJdbc.sql("SELECT has_column_privilege(:role, :table, :col, :priv)")
                .param("role", role).param("table", table)
                .param("col", column).param("priv", privilege)
                .query(Boolean.class).single();
    }

    @Test
    void negativeProbesEnforcedByEngine() {
        ReviewRun run = harness.runIntakeDirect(
                ItHarness.prEvent("ct04-d1", 1004L, "objwww/mall", 10,
                        "head" + "4".repeat(36), "opened"),
                Digest.sha256Of("ct04-diff"), Digest.sha256Of("ct04-snapshot"));
        assertThat(run).isNotNull();

        // control 写 outbox 意图成功（经 T2 铸造），但直接 UPDATE/DELETE 被引擎拒
        assertThatThrownBy(() -> controlJdbc.sql(
                "UPDATE outbox_command SET state = 'CONFIRMED'").update())
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> controlJdbc.sql(
                "DELETE FROM outbox_command").update())
                .rootCause().hasMessageContaining("permission denied");

        // publisher 不能 INSERT outbox（不能伪造写意图）
        assertThatThrownBy(() -> publisherJdbc.sql("""
                INSERT INTO outbox_command (operation_id, pr_subject_id, review_run_id, pr_revision_id,
                    aggregate_key, aggregate_sequence, publication_epoch, command_type, state,
                    policy_version, payload_hash, remote_identity_type, created_at, updated_at)
                SELECT gen_random_uuid(), s.id, r.id, r.pr_revision_id,
                    'pr:forged', 99, 0, 'CREATE_CHECK', 'PENDING',
                    'policy-v1', repeat('0', 64), 'EXTERNAL_ID', now(), now()
                  FROM pr_subject s CROSS JOIN review_run r LIMIT 1
                """).update())
                .rootCause().hasMessageContaining("permission denied");

        // publisher 游标写边界：未授权列（epoch/序号/current_revision_id）仍被引擎拒
        assertThatThrownBy(() -> publisherJdbc.sql(
                "UPDATE pr_subject SET publication_epoch = 99").update())
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> publisherJdbc.sql(
                "UPDATE pr_subject SET next_outbox_sequence = 99").update())
                .rootCause().hasMessageContaining("permission denied");
        // 授权列可写（T3-B 游标推进的生产路径），FOR UPDATE 行锁可用
        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(1004L, 10)
                .orElseThrow().getId();
        assertThat(publisherJdbc.sql(
                "UPDATE pr_subject SET last_resolved_sequence = 0, updated_at = now() WHERE id = :id")
                .param("id", subjectId).update()).isEqualTo(1);
        assertThat(publisherJdbc.sql("SELECT id FROM pr_subject WHERE id = :id FOR UPDATE")
                .param("id", subjectId).query((rs, n) -> rs.getObject(1, UUID.class)).single())
                .isEqualTo(subjectId);
    }
}
