package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.domain.tool.ToolReplayStore;
import com.objwww.pr.control.infrastructure.persistence.PostgresToolReplayStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4-32 真 PG 精确回放账本（V19）：digest 精确等值查询 + 响应字节保真（bytea 二进制）
 * + 同 digest 首录即终版（on conflict do nothing，DB 层兜底"冲突禁静默覆盖"）。
 * 键语义与冲突拒绝的应用层矩阵在 ReplayToolGatewayTest（穷举 UT）；本类证 SQL 执行面。
 */
class AlertToolReplayStoreIT extends PostgresITBase {

    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);

    private ToolReplayStore store;

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
                    rca_tool_invocation, rca_evidence, rca_evidence_snapshot,
                    rca_snapshot_member, rca_claim, rca_tool_replay
                RESTART IDENTITY CASCADE
                """).update();
        store = new PostgresToolReplayStore(controlJdbc);
    }

    @Test
    void itR1_put后按digest精确回读且二进制保真() {
        byte[] body = new byte[]{'{', '"', 'o', 'k', '"', ':', 0x00, (byte) 0xFF, '}'};

        store.put(new ToolReplayStore.ReplayRecord(DIGEST_A, "prometheus.query", "1", body));

        Optional<ToolReplayStore.ReplayRecord> found = store.find(DIGEST_A);
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().actionDigest()).isEqualTo(DIGEST_A);
        assertThat(found.orElseThrow().toolName()).isEqualTo("prometheus.query");
        assertThat(found.orElseThrow().toolVersion()).isEqualTo("1");
        assertThat(found.orElseThrow().response()).containsExactly(body);
    }

    @Test
    void itR2_无记录返回空_绝不模糊匹配() {
        store.put(new ToolReplayStore.ReplayRecord(DIGEST_A, "prometheus.query", "1",
                "ok".getBytes(StandardCharsets.UTF_8)));

        assertThat(store.find(DIGEST_B)).isEmpty();
    }

    @Test
    void itR3_同digest重复写入首录即终版_DB兜底防静默覆盖() {
        byte[] first = "first-record".getBytes(StandardCharsets.UTF_8);
        byte[] second = "mutated-record".getBytes(StandardCharsets.UTF_8);

        store.put(new ToolReplayStore.ReplayRecord(DIGEST_A, "prometheus.query", "1", first));
        store.put(new ToolReplayStore.ReplayRecord(DIGEST_A, "prometheus.query", "1", second));

        assertThat(store.find(DIGEST_A).orElseThrow().response()).containsExactly(first);
    }

    @Test
    void itR4_不同digest互不干扰_支持同工具多动作并存() {
        store.put(new ToolReplayStore.ReplayRecord(DIGEST_A, "logs.query", "1",
                "logs-body".getBytes(StandardCharsets.UTF_8)));
        store.put(new ToolReplayStore.ReplayRecord(DIGEST_B, "change.query", "1",
                "change-body".getBytes(StandardCharsets.UTF_8)));

        assertThat(store.find(DIGEST_A).orElseThrow().toolName()).isEqualTo("logs.query");
        assertThat(store.find(DIGEST_B).orElseThrow().toolName()).isEqualTo("change.query");
    }
}
