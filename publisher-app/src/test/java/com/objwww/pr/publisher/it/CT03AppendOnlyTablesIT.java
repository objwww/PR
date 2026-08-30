package com.objwww.pr.publisher.it;

import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CT-03 不可变 trigger + 角色权限双层防线：UPDATE/DELETE execution_event、pr_revision 被拒。
 * 第一层 = 角色无权限（control_app/publisher_app 只有 SELECT/INSERT）；
 * 第二层 = V1 末尾 trigger 兜底（连表 owner/admin 也改不动）。
 */
class CT03AppendOnlyTablesIT extends PostgresITBase {

    private ItHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ItHarness(casDir, null);
    }

    @Test
    void immutableTablesRejectMutationAtBothLayers() {
        ReviewRun run = harness.runIntakeDirect(
                ItHarness.prEvent("ct03-d1", 1003L, "objwww/mall", 9,
                        "head" + "3".repeat(36), "opened"),
                Digest.sha256Of("ct03-diff"), Digest.sha256Of("ct03-snapshot"));
        assertThat(count("pr_revision")).isEqualTo(1);
        assertThat(count("execution_event")).isGreaterThanOrEqualTo(1); // RUN_CREATED

        // 第一层：control_app 无 UPDATE/DELETE 权限（permission denied）
        assertThatThrownBy(() -> controlJdbc.sql(
                "UPDATE execution_event SET payload = '{}'::jsonb").update())
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> controlJdbc.sql(
                "DELETE FROM execution_event").update())
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> controlJdbc.sql(
                "UPDATE pr_revision SET head_sha = 'tampered'").update())
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> controlJdbc.sql(
                "DELETE FROM pr_revision").update())
                .rootCause().hasMessageContaining("permission denied");

        // 第一层：publisher_app 同样被拒
        assertThatThrownBy(() -> publisherJdbc.sql(
                "UPDATE execution_event SET payload = '{}'::jsonb").update())
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> publisherJdbc.sql(
                "DELETE FROM pr_revision").update())
                .rootCause().hasMessageContaining("permission denied");

        // 第二层：trigger 兜底——连超级用户 UPDATE/DELETE 也被拒（append-only 语义）
        assertThatThrownBy(() -> adminJdbc.sql(
                "UPDATE pr_revision SET head_sha = 'tampered'").update())
                .rootCause().hasMessageContaining("append-only/immutable");
        assertThatThrownBy(() -> adminJdbc.sql(
                "DELETE FROM execution_event WHERE review_run_id = '" + run.getId() + "'").update())
                .rootCause().hasMessageContaining("append-only/immutable");
    }
}
