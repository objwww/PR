package com.objwww.pr.control.it;

import com.objwww.pr.control.eval.domain.EvalRun;
import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.model.EvalRunCommand;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalRunRow;
import com.objwww.pr.control.eval.domain.statemachine.EvalRunLifecycle;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvalPhaseEventSink;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvalQueryReader;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvalRunCommandRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvalRunRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PermissionDeniedDataAccessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EV-04 验收（真 PG）：V81 评测发起与生命周期——
 * eval_run_command 授权矩阵（control_app 只增不查改、eval_app 列级状态推进、
 * publisher/notify 显式拒绝）、eval_phase_event insert-only（V80 授权复核）、
 * 幂等锚 uq 撞键、取消受理后读面 cancel_requested_at 演进、
 * recovery_state/terminal_reason/launch_plan 三列的写读闭环。
 *
 * <p>写面分工：control_app 身份（controlJdbc）只提交命令；eval_app 身份
 * （evalJdbc）= worker（领取/推进/落 run 行/落阶段事件）。
 */
class PostgresEvalLifecycleIT extends PostgresITBase {

    private PostgresEvalRunRepository evalRuns;
    private PostgresEvalQueryReader reader;
    private PostgresEvalRunCommandRepository controlCommands;
    private PostgresEvalRunCommandRepository workerCommands;
    private PostgresEvalPhaseEventSink phaseSink;

    @BeforeEach
    void setUpRepositories() {
        evalRuns = new PostgresEvalRunRepository(evalJdbc);
        reader = new PostgresEvalQueryReader(controlJdbc);
        controlCommands = new PostgresEvalRunCommandRepository(controlJdbc);
        workerCommands = new PostgresEvalRunCommandRepository(evalJdbc);
        phaseSink = new PostgresEvalPhaseEventSink(evalJdbc);
    }

    private static EvalRunMetadata metadata() {
        return new EvalRunMetadata(1, "eval-ds-1", Digest.sha256Of("registry"), 1,
                "deepseek-v3", "am3-rca-v2", Digest.sha256Of("prompt"),
                Digest.sha256Of("tools"), new BigDecimal("0.7"), new BigDecimal("0.9"),
                null, null, null,
                "litellm/internal-v1#key-eval", Digest.sha256Of("rules"),
                "scenario-driver-v1", "grader-test-v1");
    }

    private EvalRunCommand launchCommand(UUID runId, String key, String planJson) {
        return EvalRunCommand.pending(UUID.randomUUID(), EvalRunCommand.Type.LAUNCH,
                runId, key, planJson, Digest.sha256Of(planJson).value(),
                "operator", Instant.now());
    }

    // ------------------------------------------------------------------ EU10 授权矩阵

    @Test
    @DisplayName("EU10 授权矩阵：control_app 可提交命令（insert/select），不可改写命令"
            + "（update/delete 拒）；publisher/notify 显式拒绝")
    void commandTableGrantMatrix() {
        UUID runId = UUID.randomUUID();
        controlCommands.insert(launchCommand(runId, "k-grant", "{\"mode\":\"E\"}"));
        assertThat(controlCommands.findByKey(EvalRunCommand.Type.LAUNCH, "k-grant"))
                .isPresent();

        // control_app 零 update/delete 开口（命令落库即冻结）
        assertThatThrownBy(() -> controlJdbc.sql(
                "UPDATE eval_run_command SET state = 'CLAIMED'").update())
                .isInstanceOf(PermissionDeniedDataAccessException.class);
        assertThatThrownBy(() -> controlJdbc.sql(
                "DELETE FROM eval_run_command").update())
                .isInstanceOf(PermissionDeniedDataAccessException.class);

        // control_app 对 eval_run 仍只读（V45 面不破）
        assertThatThrownBy(() -> controlJdbc.sql(
                "UPDATE eval_run SET state = 'FAILED'").update())
                .isInstanceOf(PermissionDeniedDataAccessException.class);

        // publisher/notify 显式拒绝（写面与读面皆无）
        assertThatThrownBy(() -> publisherJdbc.sql(
                "SELECT count(*) FROM eval_run_command").query(Long.class).single())
                .isInstanceOf(PermissionDeniedDataAccessException.class);
        assertThatThrownBy(() -> notifyJdbc.sql(
                "SELECT count(*) FROM eval_run_command").query(Long.class).single())
                .isInstanceOf(PermissionDeniedDataAccessException.class);
    }

    @Test
    @DisplayName("eval_app（worker）列级推进：state/worker_id/claimed_at 可写，"
            + "正文列（payload）拒；阶段事件 insert-only（update 拒）")
    void workerColumnGrantsAndPhaseEventInsertOnly() {
        UUID runId = UUID.randomUUID();
        controlCommands.insert(launchCommand(runId, "k-worker", "{\"mode\":\"E\"}"));

        EvalRunCommand claimed = workerCommands
                .claimNextLaunch("worker-it", Instant.now()).orElseThrow();
        assertThat(claimed.state()).isEqualTo(EvalRunCommand.State.CLAIMED);
        assertThat(claimed.workerId()).isEqualTo("worker-it");
        // 重复领取：PENDING 已空
        assertThat(workerCommands.claimNextLaunch("worker-it", Instant.now())).isEmpty();
        // eval_app 正文列零开口
        assertThatThrownBy(() -> evalJdbc.sql(
                "UPDATE eval_run_command SET payload = '{}'::jsonb").update())
                .isInstanceOf(PermissionDeniedDataAccessException.class);
        // 命令终态推进
        assertThat(workerCommands.finish(claimed.id(), EvalRunCommand.State.DONE,
                Instant.now())).isTrue();

        // eval_phase_event：eval_app 可插不可改（V80 insert-only 面复核）
        EvalRun run = EvalRun.running(runId, metadata(), Instant.now());
        evalRuns.insertRunning(run);
        phaseSink.record(runId, "PREPARING", Instant.now(), "worker-it", null);
        assertThatThrownBy(() -> evalJdbc.sql(
                "UPDATE eval_phase_event SET phase = 'SCORING'").update())
                .isInstanceOf(PermissionDeniedDataAccessException.class);
    }

    // ------------------------------------------------------------------ EU09 幂等锚

    @Test
    @DisplayName("EU09 幂等锚：同 (type, key) 二次插入撞 uq 抛 DuplicateKeyException")
    void idempotencyAnchorRejectsDuplicateKey() {
        controlCommands.insert(launchCommand(UUID.randomUUID(), "k-dup", "{\"a\":1}"));
        assertThatThrownBy(() -> controlCommands.insert(
                launchCommand(UUID.randomUUID(), "k-dup", "{\"b\":2}")))
                .isInstanceOf(DuplicateKeyException.class);
        // 异 type 同 key 不冲突（LAUNCH/CANCEL 各自键空间）
        UUID runId = UUID.randomUUID();
        EvalRunCommand cancel = EvalRunCommand.pending(UUID.randomUUID(),
                EvalRunCommand.Type.CANCEL, runId, "k-dup", "{}",
                Digest.sha256Of("cancel").value(), "operator", Instant.now());
        controlCommands.insert(cancel);
    }

    // ------------------------------------------------------------------ 取消后读面演进

    @Test
    @DisplayName("取消受理后读面演进：cancel_requested_at 即刻可见；worker 落身份/恢复"
            + "分面/卡因后 detail 投影全真值")
    void readSideEvolvesWithLifecycle() {
        UUID runId = UUID.randomUUID();
        controlCommands.insert(launchCommand(runId, "k-life",
                "{\"displayName\":\"实验甲\",\"mode\":\"L\"}"));
        // worker 领取 → 落 run 行 + 身份 + PREPARING + L 恢复挂账
        workerCommands.claimNextLaunch("worker-it", Instant.now());
        evalRuns.insertRunning(EvalRun.running(runId, metadata(), Instant.now()));
        evalRuns.applyLaunchIdentity(runId, "实验甲", "L",
                "{\"displayName\":\"实验甲\",\"mode\":\"L\"}");
        phaseSink.record(runId, "PREPARING", Instant.now(), "worker-it", null);
        evalRuns.updateRecoveryState(runId, EvalRunLifecycle.RECOVERY_PENDING);

        EvalRunRow before = reader.findRun(runId).orElseThrow();
        assertThat(before.displayName()).isEqualTo("实验甲");
        assertThat(before.mode()).isEqualTo("L");
        assertThat(before.phase()).isEqualTo("PREPARING");
        assertThat(before.recoveryState()).isEqualTo("PENDING");
        assertThat(before.cancelRequestedAt()).isNull();
        assertThat(before.launchPlanJson()).contains("实验甲");

        // 取消受理（control_app 只增命令）→ 读面 cancel_requested_at 立即真值
        EvalRunCommand cancel = EvalRunCommand.pending(UUID.randomUUID(),
                EvalRunCommand.Type.CANCEL, runId, "k-cancel-1", "{\"reason\":\"误发\"}",
                Digest.sha256Of("cancel-1").value(), "operator", Instant.now());
        controlCommands.insert(cancel);
        assertThat(workerCommands.cancelAccepted(runId)).isTrue();

        EvalRunRow cancelling = reader.findRun(runId).orElseThrow();
        assertThat(cancelling.cancelRequestedAt()).isNotNull();
        assertThat(cancelling.state()).isEqualTo("RUNNING");   // 受理 ≠ 已停

        // worker 恢复核验 → 终态
        phaseSink.record(runId, "RECOVERING", Instant.now(), "worker-it",
                "{\"reason\":\"cancel\"}");
        evalRuns.updateRecoveryState(runId, EvalRunLifecycle.RECOVERY_RECOVERING);
        evalRuns.updateRecoveryState(runId, EvalRunLifecycle.RECOVERY_VERIFIED);
        evalRuns.finalizeOnce(EvalRun.terminal(runId, metadata(),
                EvalRun.EvalRunState.FAILED, Instant.now(), Instant.now(),
                null, null, null, "cancelled_by_operator;recovery=VERIFIED"));
        workerCommands.finish(
                workerCommands.findByKey(EvalRunCommand.Type.LAUNCH, "k-life")
                        .orElseThrow().id(),
                EvalRunCommand.State.DONE, Instant.now());

        EvalRunRow done = reader.findRun(runId).orElseThrow();
        assertThat(done.state()).isEqualTo("FAILED");
        assertThat(done.phase()).isEqualTo("RECOVERING");
        assertThat(done.recoveryState()).isEqualTo("VERIFIED");
        assertThat(done.terminalReason())
                .isEqualTo("cancelled_by_operator;recovery=VERIFIED");
        assertThat(done.cancelRequestedAt()).isNotNull();
    }

    // ------------------------------------------------------------------ 崩溃孤儿

    @Test
    @DisplayName("EU15 孤儿面：CLAIMED 超龄且 run 未落库 → 重排队；run 仍 RUNNING → "
            + "worker_lost 终态化且命令对齐 FAILED")
    void orphanSweepRequeuesOrFinalizes() {
        Instant stale = Instant.now().minusSeconds(3600);
        // 孤儿 A：CLAIMED 但 run 从未落库 → requeue
        UUID runA = UUID.randomUUID();
        controlCommands.insert(launchCommand(runA, "k-orphan-a", "{\"mode\":\"E\"}"));
        workerCommands.claimNextLaunch("dead-worker", stale);
        // 孤儿 B：CLAIMED 且 run 仍 RUNNING → worker_lost 终态
        UUID runB = UUID.randomUUID();
        controlCommands.insert(launchCommand(runB, "k-orphan-b", "{\"mode\":\"L\"}"));
        workerCommands.claimNextLaunch("dead-worker", stale);
        evalRuns.insertRunning(EvalRun.running(runB, metadata(), Instant.now()));

        var orphans = workerCommands.findOrphanedClaims(Instant.now().minusSeconds(900));
        assertThat(orphans).hasSize(2);

        // A：重排队（稳定身份——预定 id 不变）
        assertThat(workerCommands.requeue(
                orphans.stream().filter(c -> c.evalRunId().equals(runA))
                        .findFirst().orElseThrow().id())).isTrue();
        EvalRunCommand requeued = workerCommands
                .findByKey(EvalRunCommand.Type.LAUNCH, "k-orphan-a").orElseThrow();
        assertThat(requeued.state()).isEqualTo(EvalRunCommand.State.PENDING);
        assertThat(requeued.evalRunId()).isEqualTo(runA);

        // B：worker_lost 终态化（L 模式 recovery 保持 PENDING = 未核验，不冒充）
        evalRuns.finalizeOnce(EvalRun.terminal(runB, metadata(),
                EvalRun.EvalRunState.FAILED, Instant.now(), Instant.now(),
                null, null, null, EvalRunLifecycle.orphanTerminalReason("L")));
        EvalRun stored = evalRuns.findById(runB).orElseThrow();
        assertThat(stored.state()).isEqualTo(EvalRun.EvalRunState.FAILED);
        assertThat(stored.terminalReason()).isEqualTo("worker_lost;recovery_unverified");
        assertThat(reader.findRun(runB).orElseThrow().recoveryState()).isNull();
    }
}
