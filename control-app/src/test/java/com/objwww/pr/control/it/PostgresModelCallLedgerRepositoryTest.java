package com.objwww.pr.control.it;

import com.objwww.pr.control.domain.ai.ModelCallLedgerEntry;
import com.objwww.pr.control.domain.ai.TokenUsage;
import com.objwww.pr.control.infrastructure.persistence.PostgresModelCallLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgresModelCallLedgerRepository 真 PG 组件测试（INC-60 回归）：
 * pgjdbc 不支持直接绑定 {@link Instant} 参数（SQLState 07006，Spring 侧包装为
 * BadSqlGrammarException），{@code markUnknownOlderThan} 曾因此每分钟静默失败、
 * 超龄 STARTED 永远不被标 UNKNOWN——此前该方法无任何真 PG 覆盖（只有单测假仓储）。
 *
 * <p>同时打通 insertStarted → completeTerminalSuccess 主写路径（真实 control_app
 * 列级授权 + V5 CHECK 约束兜底）。
 */
class PostgresModelCallLedgerRepositoryTest extends PostgresITBase {

    private PostgresModelCallLedgerRepository repo;
    private RepairSeed seed;
    private UUID stepId;
    private UUID attemptId;

    @BeforeEach
    void setUp() {
        repo = new PostgresModelCallLedgerRepository(new JdbcTemplate(controlDataSource()));
        seed = seedRepairScope("ledger");
        // model_call_ledger 的 FK 前提：run_step + work_item + step_attempt
        stepId = UUID.randomUUID();
        UUID workItemId = UUID.randomUUID();
        attemptId = UUID.randomUUID();
        adminJdbc.sql("""
                INSERT INTO run_step(id,review_run_id,step_key,operation_id,step_type,state,
                    ordinal,timeout_seconds,created_at,updated_at)
                VALUES (:id,:run,'step-ledger',:op,'REVIEW','READY',1,600,now(),now())
                """).param("id", stepId).param("run", seed.runId()).param("op", UUID.randomUUID())
                .update();
        adminJdbc.sql("""
                INSERT INTO work_item(id,review_run_id,step_id,work_type,state,available_at,
                    max_attempts,created_at,updated_at)
                VALUES (:id,:run,:step,'REVIEW','READY',now(),3,now(),now())
                """).param("id", workItemId).param("run", seed.runId()).param("step", stepId)
                .update();
        adminJdbc.sql("""
                INSERT INTO step_attempt(id,step_id,work_item_id,attempt_no,lease_epoch,
                    worker_id,status,started_at)
                VALUES (:id,:step,:wi,1,1,'it-worker','STARTED',now())
                """).param("id", attemptId).param("step", stepId).param("wi", workItemId)
                .update();
    }

    private ModelCallLedgerEntry newEntry(int callSeq) {
        return ModelCallLedgerEntry.builder()
                .id(UUID.randomUUID())
                .invocationId(UUID.randomUUID())
                .callSeq(callSeq)
                .reviewRunId(seed.runId())
                .runStepId(stepId)
                .attemptId(attemptId)
                .leaseEpoch(1)
                .routeId("it-route")
                .routeRole("PRIMARY")
                .endpointScope("it-endpoint")
                .quotaScope("it-quota")
                .requestedModel("it-model")
                .build();
    }

    private String stateOf(UUID ledgerId) {
        return adminJdbc.sql("SELECT state FROM model_call_ledger WHERE id=:id")
                .param("id", ledgerId).query(String.class).single();
    }

    /** INC-60：超龄 STARTED 标 UNKNOWN（Timestamp 绑定），新鲜的与已终结的不动，重复扫描幂等。 */
    @Test
    void markUnknownOlderThanMarksOnlyStaleStartedAndIsIdempotent() {
        ModelCallLedgerEntry stale = newEntry(1);
        ModelCallLedgerEntry fresh = newEntry(2);
        repo.insertStarted(stale);
        repo.insertStarted(fresh);
        // 拨老一行的 started_at（admin 测试动作；应用路径 started_at 恒为 now()）
        adminJdbc.sql("UPDATE model_call_ledger SET started_at = now() - interval '10 minutes' WHERE id=:id")
                .param("id", stale.id()).update();

        int marked = repo.markUnknownOlderThan(Instant.now().minusSeconds(240));

        assertThat(marked).isEqualTo(1);
        assertThat(stateOf(stale.id())).isEqualTo("UNKNOWN");
        assertThat(adminJdbc.sql("SELECT finished_at IS NOT NULL FROM model_call_ledger WHERE id=:id")
                .param("id", stale.id()).query(Boolean.class).single()).isTrue();
        assertThat(stateOf(fresh.id())).isEqualTo("STARTED");
        // UNKNOWN 是终态（R-M1）：重复扫描零改写
        assertThat(repo.markUnknownOlderThan(Instant.now().minusSeconds(240))).isZero();
    }

    /** 主写路径：insertStarted → completeTerminalSuccess，真实授权与 CHECK 约束兜底。 */
    @Test
    void insertThenCompleteSuccessRoundtrip() {
        ModelCallLedgerEntry entry = newEntry(1);
        repo.insertStarted(entry);
        assertThat(stateOf(entry.id())).isEqualTo("STARTED");

        boolean ok = repo.completeTerminalSuccess(entry.id(), new TokenUsage(100, 50, 150),
                false, "it-model-reported", "req-1", null, null, null, null, null, null);

        assertThat(ok).isTrue();
        assertThat(stateOf(entry.id())).isEqualTo("SUCCEEDED");
        // 终态不可改写（WHERE state='STARTED' 条件更新）
        assertThat(repo.completeTerminalSuccess(entry.id(), new TokenUsage(1, 1, 2),
                false, "x", "req-2", null, null, null, null, null, null)).isFalse();
    }
}
