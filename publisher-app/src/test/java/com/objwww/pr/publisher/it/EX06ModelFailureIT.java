package com.objwww.pr.publisher.it;

import com.objwww.pr.control.domain.ai.ModelBudgetExceededException;
import com.objwww.pr.control.domain.ai.ModelCallFailedException;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.shared.AttemptStatus;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.RunState;
import com.objwww.pr.shared.StepState;
import com.objwww.pr.shared.WorkItemState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-06 模型超时/预算超限（§6.6）：Step FAILED（安全步骤不降级）；
 * Run 进入 FAILED 可恢复态；预算事件落账。另演示超时（可重试）→ 退避后重跑成功。
 */
class EX06ModelFailureIT extends PostgresITBase {

    private ItHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ItHarness(casDir, null);
    }

    @Test
    void budgetExceededFailsStepAndLedgersEvent() {
        ReviewRun run = harness.dispatchOpened(
                ItHarness.prEvent("ex06-d1", 3006L, "objwww/mall", 36,
                        "head" + "5".repeat(36), "opened"),
                ItTarballs.singleFile("src/A.java", "class A {}\n"), "diff");

        // M3：预算闸在真 ModelGateway 内（ModelStepBudgetGuard，单测覆盖）；IT 线束用 mock
        // gateway 不走预算闸，故按接口契约直接入队 ModelBudgetExceededException——
        // 本用例钉的是 WorkItemWorker/orchestrator 对预算拒绝的 Step 级语义（§6.6 不降级）。
        harness.modelClient.enqueueFailure(
                new ModelBudgetExceededException("completion 9000 > max-completion-tokens-per-call 8000"));
        harness.newWorker("worker-1").runOnce();

        assertThat(harness.runRepo.findById(run.getId()).orElseThrow().getState())
                .isEqualTo(RunState.FAILED);
        var step = harness.stepRepo.findByRunId(run.getId()).get(0);
        assertThat(step.getState()).isEqualTo(StepState.FAILED);
        var attempt = harness.attemptRepo.findByStepId(step.getId()).get(0);
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.FAILED_TERMINAL);
        assertThat(attempt.getErrorCode()).isEqualTo("MODEL_BUDGET_EXCEEDED");
        assertThat(harness.workItemRepo.findByStepId(step.getId()).orElseThrow().getState())
                .isEqualTo(WorkItemState.DEAD);
        assertThat(count("outbox_command")).isZero(); // 失败的评审绝不产发布意图
        assertThat(harness.eventsOf(run.getId()).stream()
                .anyMatch(e -> e.eventType() == ExecutionEventType.BUDGET_EXCEEDED)).isTrue();
    }

    @Test
    void timeoutIsRetryableAndRecovers() {
        ReviewRun run = harness.dispatchOpened(
                ItHarness.prEvent("ex06-d2", 3007L, "objwww/mall", 37,
                        "head" + "6".repeat(36), "opened"),
                ItTarballs.singleFile("src/A.java", "class A {}\n"), "diff");

        // 第 1 次超时（可重试）→ RETRY_WAIT 退避；Run 不终态
        // M3：ModelTimeoutException 已删除，超时经 ModelCallFailedException("TIMEOUT", retryable=true) 承载
        harness.modelClient.enqueueFailure(new ModelCallFailedException("TIMEOUT", "模型超时", true));
        harness.newWorker("worker-1").runOnce();
        var step = harness.stepRepo.findByRunId(run.getId()).get(0);
        assertThat(step.getState()).isEqualTo(StepState.WAITING);
        assertThat(harness.workItemRepo.findByStepId(step.getId()).orElseThrow().getState())
                .isEqualTo(WorkItemState.RETRY_WAIT);
        assertThat(harness.runRepo.findById(run.getId()).orElseThrow().getState())
                .isEqualTo(RunState.CREATED);

        // 退避到期 → 重领重跑成功
        adminJdbc.sql("UPDATE work_item SET available_at = :past WHERE step_id = :id")
                .param("past", Timestamp.from(Instant.now().minusSeconds(1)))
                .param("id", step.getId()).update();
        harness.modelClient.enqueueContent("[]");
        harness.newWorker("worker-1").runOnce();

        assertThat(harness.runRepo.findById(run.getId()).orElseThrow().getState())
                .isEqualTo(RunState.REVIEW_COMPLETE);
        assertThat(harness.stepRepo.findById(step.getId()).orElseThrow().getState())
                .isEqualTo(StepState.SUCCEEDED);
        assertThat(harness.attemptRepo.findByStepId(step.getId()).stream()
                .map(a -> a.getStatus()).toList())
                .containsExactlyInAnyOrder(AttemptStatus.FAILED_RETRYABLE, AttemptStatus.SUCCEEDED);
        assertThat(count("outbox_command")).isEqualTo(2);
    }
}
