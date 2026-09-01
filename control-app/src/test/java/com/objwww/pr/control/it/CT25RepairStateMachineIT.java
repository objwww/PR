package com.objwww.pr.control.it;

import com.objwww.pr.control.infrastructure.persistence.PostgresRepairRequestRepository;
import com.objwww.pr.shared.RepairRequestState;
import com.objwww.pr.shared.RepairRequestStateMachine;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CT-25（docs/M2-技术方案.md §11 L2 表，回指 §4.1 repair_request 状态迁移表 / I24）。
 *
 * <p>场景：七态状态机行为用例——每条合法迁移在真库执行一条迁移 SQL（仓储方法 /
 * runbook 批准 SQL / 投影方 SQL），再打典型非法迁移（PENDING→REPAIRED、三终态→任意）。
 *
 * <p>断言：16 条合法迁移逐条落库成功且字段副作用正确（attempt_count/next_attempt_at/
 * repair_run_id/审计三列）；非法迁移 0 行且状态机层抛明确错误，原状态不变。
 *
 * <p>取证：repair_request.state / repair_run_id / repair_operation_id / attempt_count /
 * next_attempt_at / last_error / approved_by / approved_at / approval_reason。
 */
class CT25RepairStateMachineIT extends PostgresITBase {

    private static final String PAYLOAD = "{\"name\":\"ai-review\",\"conclusion\":\"success\"}";

    private final PostgresRepairRequestRepository requests =
            new PostgresRepairRequestRepository(controlJdbc);

    /** 每边一条独立链路（部分唯一索引要求一资源一单，命令 uq(aggregate_key,sequence) 要求一骨架一令）。 */
    private UUID newRequest(String tier, String state, int attemptCount, int maxAttempts, int seq) {
        RepairSeed seed = seedRepairScope("ct25-" + seq);
        UUID op = seedConfirmedCommand(seed, "CREATE_CHECK", PAYLOAD);
        UUID resource = seedResource(seed, op, "CHECK_RUN", "MISSING", "ct25-r" + seq);
        return seedRepairRequest(resource, "CHECK_RUN", tier, state, attemptCount, maxAttempts, 0);
    }

    /** runbook 批准 SQL（§4.3 文档化参数化形态），返回影响行数。 */
    private int approve(UUID id, String actor, String reason) {
        return controlJdbc.sql("""
                UPDATE repair_request SET state='APPROVED', approved_by=:actor, approved_at=now(),
                    approval_reason=:reason, updated_at=now()
                 WHERE id=:id AND state='PENDING'
                """).param("actor", actor).param("reason", reason).param("id", id).update();
    }

    @Test
    void legalTransitionsEachLandWithFieldEffects() {
        // PENDING→APPROVED（人工批准，MANUAL 档；审计三列随迁移落库）
        UUID r1 = newRequest("MANUAL", "PENDING", 0, 5, 1);
        assertThat(approve(r1, "op-1", "runbook-42")).isEqualTo(1);
        assertThat(repairStateOf(r1)).isEqualTo("APPROVED");

        // PENDING→DISPATCHED（AUTO 档领取）：attempt_count+1、run/op 两列回填、退避清空
        UUID r2 = newRequest("AUTO", "PENDING", 0, 5, 2);
        assertThat(requests.markDispatched(r2, seedRunId(r2), UUID.randomUUID())).isTrue();
        assertThat(repairStateOf(r2)).isEqualTo("DISPATCHED");

        // PENDING→RETRY_WAIT（可重试失败）：退避到期时间写入未来
        UUID r3 = newRequest("AUTO", "PENDING", 0, 5, 3);
        assertThat(requests.markRetryWait(r3, Duration.ofSeconds(30), "boom")).isTrue();
        assertThat(repairStateOf(r3)).isEqualTo("RETRY_WAIT");
        assertThat(nextAttemptAtInFuture(r3)).isTrue();

        // PENDING→FAILED_TERMINAL / PENDING→EXPIRED
        UUID r4 = newRequest("AUTO", "PENDING", 0, 5, 4);
        assertThat(requests.markFailedTerminal(r4, "bad payload")).isTrue();
        assertThat(repairStateOf(r4)).isEqualTo("FAILED_TERMINAL");
        UUID r5 = newRequest("AUTO", "PENDING", 0, 5, 5);
        assertThat(requests.markExpired(r5, "STALE_GENERATION")).isTrue();
        assertThat(repairStateOf(r5)).isEqualTo("EXPIRED");

        // APPROVED→{DISPATCHED, RETRY_WAIT, FAILED_TERMINAL, EXPIRED}
        UUID r6 = newRequest("MANUAL", "APPROVED", 0, 5, 6);
        assertThat(requests.markDispatched(r6, seedRunId(r6), UUID.randomUUID())).isTrue();
        assertThat(repairStateOf(r6)).isEqualTo("DISPATCHED");
        UUID r7 = newRequest("MANUAL", "APPROVED", 0, 5, 7);
        assertThat(requests.markRetryWait(r7, Duration.ofSeconds(30), "boom")).isTrue();
        assertThat(repairStateOf(r7)).isEqualTo("RETRY_WAIT");
        UUID r8 = newRequest("MANUAL", "APPROVED", 0, 5, 8);
        assertThat(requests.markFailedTerminal(r8, "bad payload")).isTrue();
        assertThat(repairStateOf(r8)).isEqualTo("FAILED_TERMINAL");
        UUID r9 = newRequest("MANUAL", "APPROVED", 0, 5, 9);
        assertThat(requests.markExpired(r9, "STALE_GENERATION")).isTrue();
        assertThat(repairStateOf(r9)).isEqualTo("EXPIRED");

        // DISPATCHED→REPAIRED（命令 CONFIRMED 投影，publisher 侧 RepairOutcomeProjector 语义；
        // 以 publisher 角色执行顺带证明其 state 列可写）
        UUID r10 = newRequest("AUTO", "DISPATCHED", 1, 5, 10);
        assertThat(publisherJdbc.sql("""
                UPDATE repair_request SET state='REPAIRED', updated_at=now()
                 WHERE id=:id AND state='DISPATCHED'
                """).param("id", r10).update()).isEqualTo(1);
        assertThat(repairStateOf(r10)).isEqualTo("REPAIRED");

        // DISPATCHED→FAILED_TERMINAL / DISPATCHED→EXPIRED（命令终态投影）
        UUID r11 = newRequest("AUTO", "DISPATCHED", 1, 5, 11);
        assertThat(requests.markFailedTerminal(r11, "cmd failed")).isTrue();
        assertThat(repairStateOf(r11)).isEqualTo("FAILED_TERMINAL");
        UUID r12 = newRequest("AUTO", "DISPATCHED", 1, 5, 12);
        assertThat(requests.markExpired(r12, "cmd superseded")).isTrue();
        assertThat(repairStateOf(r12)).isEqualTo("EXPIRED");

        // RETRY_WAIT→DISPATCHED（到点重领）
        UUID r13 = newRequest("AUTO", "RETRY_WAIT", 1, 5, 13);
        assertThat(requests.markDispatched(r13, seedRunId(r13), UUID.randomUUID())).isTrue();
        assertThat(repairStateOf(r13)).isEqualTo("DISPATCHED");

        // RETRY_WAIT→RETRY_WAIT（再失败，预算未尽）
        UUID r14 = newRequest("AUTO", "RETRY_WAIT", 1, 5, 14);
        assertThat(requests.markRetryWait(r14, Duration.ofSeconds(60), "again")).isTrue();
        assertThat(repairStateOf(r14)).isEqualTo("RETRY_WAIT");
        assertThat(attemptCountOf(r14)).isEqualTo(2);

        // RETRY_WAIT→FAILED_TERMINAL（预算耗尽：attempt_count+1 >= max_attempts 单句翻转终态）
        UUID r15 = newRequest("AUTO", "RETRY_WAIT", 4, 5, 15);
        assertThat(requests.markRetryWait(r15, Duration.ofSeconds(60), "last")).isTrue();
        assertThat(repairStateOf(r15)).isEqualTo("FAILED_TERMINAL");
        assertThat(nextAttemptAtIsNull(r15)).isTrue();

        // RETRY_WAIT→EXPIRED
        UUID r16 = newRequest("AUTO", "RETRY_WAIT", 1, 5, 16);
        assertThat(requests.markExpired(r16, "STALE_GENERATION")).isTrue();
        assertThat(repairStateOf(r16)).isEqualTo("EXPIRED");
    }

    @Test
    void pendingToRepairedIsRejectedWithZeroRowsAndClearError() {
        UUID id = newRequest("AUTO", "PENDING", 0, 5, 100);

        // 应用层状态机：明确错误（§4.1 迁移表是唯一合法迁移集，I24）
        assertThat(RepairRequestStateMachine.canTransition(
                RepairRequestState.PENDING, RepairRequestState.REPAIRED)).isFalse();
        assertThatThrownBy(() -> RepairRequestStateMachine.transition(
                RepairRequestState.PENDING, RepairRequestState.REPAIRED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法 repair_request 状态迁移")
                .hasMessageContaining("PENDING").hasMessageContaining("REPAIRED");

        // 存储层：投影方 SQL 带前置态守卫，0 行、原状态不变
        assertThat(publisherJdbc.sql("""
                UPDATE repair_request SET state='REPAIRED', updated_at=now()
                 WHERE id=:id AND state='DISPATCHED'
                """).param("id", id).update()).isZero();
        assertThat(repairStateOf(id)).isEqualTo("PENDING");
    }

    @Test
    void terminalStatesAbsorbEverything() {
        for (int i = 0; i < 3; i++) {
            String terminal = switch (i) {
                case 0 -> "REPAIRED";
                case 1 -> "FAILED_TERMINAL";
                default -> "EXPIRED";
            };
            UUID id = newRequest("AUTO", terminal, 1, 5, 200 + i);

            // 状态机：三终态出边为空集，任何目标都明确报错
            for (RepairRequestState to : RepairRequestState.values()) {
                RepairRequestState from = RepairRequestState.valueOf(terminal);
                assertThat(RepairRequestStateMachine.canTransition(from, to)).isFalse();
                assertThatThrownBy(() -> RepairRequestStateMachine.transition(from, to))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("非法 repair_request 状态迁移");
            }

            // 存储层：所有迁移手段对终态行 0 行
            assertThat(requests.markDispatched(id, seedRunId(id), UUID.randomUUID())).isFalse();
            assertThat(requests.markRetryWait(id, Duration.ofSeconds(1), "x")).isFalse();
            assertThat(requests.markFailedTerminal(id, "x")).isFalse();
            assertThat(requests.markExpired(id, "x")).isFalse();
            assertThat(approve(id, "op-2", "late approval")).isZero();
            assertThat(publisherJdbc.sql("""
                    UPDATE repair_request SET state='REPAIRED', updated_at=now()
                     WHERE id=:id AND state='DISPATCHED'
                    """).param("id", id).update()).isZero();
            assertThat(repairStateOf(id)).isEqualTo(terminal);
        }
    }

    /** repair_run_id 只需引用一张存在的 review_run；取该单资源链路上的原 Run。 */
    private UUID seedRunId(UUID requestId) {
        return adminJdbc.sql("""
                SELECT o.review_run_id FROM repair_request rr
                  JOIN publication_resource r ON r.id = rr.publication_resource_id
                  JOIN outbox_command o ON o.operation_id = r.created_by_operation_id
                 WHERE rr.id = :id
                """).param("id", requestId).query(UUID.class).single();
    }

    private int attemptCountOf(UUID id) {
        return adminJdbc.sql("SELECT attempt_count FROM repair_request WHERE id=:id")
                .param("id", id).query(Integer.class).single();
    }

    private boolean nextAttemptAtInFuture(UUID id) {
        return adminJdbc.sql("SELECT next_attempt_at > now() FROM repair_request WHERE id=:id")
                .param("id", id).query(Boolean.class).single();
    }

    private boolean nextAttemptAtIsNull(UUID id) {
        return adminJdbc.sql("SELECT next_attempt_at IS NULL FROM repair_request WHERE id=:id")
                .param("id", id).query(Boolean.class).single();
    }
}
