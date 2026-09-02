package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.model.PrSubjectState;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.model.RunStep;
import com.objwww.pr.control.domain.model.StepAttempt;
import com.objwww.pr.control.domain.model.WorkItem;
import com.objwww.pr.control.domain.review.ReviewFindingDraft;
import com.objwww.pr.control.domain.review.ReviewOutcome;
import com.objwww.pr.control.domain.ai.ModelRouteIdentity;
import com.objwww.pr.control.domain.ai.TokenUsage;
import com.objwww.pr.control.support.OrchestratorFixture;
import com.objwww.pr.shared.AttemptStatus;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.OutboxCommand;
import com.objwww.pr.shared.RunState;
import com.objwww.pr.shared.StepState;
import com.objwww.pr.shared.WorkItemState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2 完成 Step 事务脚本（§6.1 逐条对照 + I11 晚到结果栅栏）。
 * 内存假实现模拟约束行为；真实 PG 的唯一约束/事务隔离在服务器侧 CT 级统一验证。
 */
class ReviewOrchestratorT2Test {

    private OrchestratorFixture fx;
    private ReviewRun run;
    private RunStep step;
    private WorkItem workItem;
    private StepAttempt attempt;

    @BeforeEach
    void setUp() {
        fx = new OrchestratorFixture();
        run = fx.orchestrator.runIntake(new IntakeCommand(987L, 12345L, "org/repo", 7,
                PrSubjectState.OPEN, false, false,
                "head1", "main", "basesha456", null,
                Digest.sha256Of("diff"), Digest.sha256Of("snap"),
                "m0-policy-v1", "m0-prompt-v1", "m0-toolset-v1", "d-1", null));
        step = fx.steps.findByRunId(run.getId()).get(0);
        workItem = fx.workItems.findByStepId(step.getId()).orElseThrow();
        // worker 领租约（leaseEpoch 0→1, attemptCount 0→1）并记 STARTED attempt
        workItem.leaseTo("worker-1", Instant.now().plusSeconds(60), Instant.now());
        fx.workItems.save(workItem);
        attempt = new StepAttempt(UUID.randomUUID(), step.getId(), workItem.getId(),
                1, workItem.getLeaseEpoch(), "worker-1", AttemptStatus.STARTED,
                null, "mock-model", null, null, null, null, null,
                Instant.now(), null);
        fx.attempts.save(attempt);
    }

    private StepCompletion completion(StepOutcome outcome) {
        return new StepCompletion(workItem.getId(), step.getId(), attempt.getId(),
                "worker-1", workItem.getLeaseEpoch(), outcome);
    }

    private static StepOutcome successOutcome(int findingCount) {
        List<ReviewFindingDraft> drafts = new java.util.ArrayList<>();
        for (int i = 0; i < findingCount; i++) {
            drafts.add(new ReviewFindingDraft("a/Foo.java", 3 + i, 3 + i,
                    "rule-" + i, "MAJOR", "msg " + i, Digest.sha256Of("fp-" + i)));
        }
        return new StepOutcome.Succeeded(Digest.sha256Of("output"),
                new ReviewOutcome(drafts, 1, 0, 5, 4, 1, new TokenUsage(10, 20, 30), "[]",
                        new ModelRouteIdentity("mock-provider", "mock-model", "v1")));
    }

    @Test
    void staleLeaseRecordedWithoutAdvancing() {
        // I11：租约已易主（epoch 不符）→ attempt 记 STALE，Step/Run/WorkItem 一律不推进
        StepCompletion stale = new StepCompletion(workItem.getId(), step.getId(), attempt.getId(),
                "worker-1", workItem.getLeaseEpoch() + 9, successOutcome(2));

        T2Outcome result = fx.orchestrator.completeStep(stale);

        assertThat(result).isEqualTo(T2Outcome.STALE_IGNORED);
        assertThat(fx.attempts.findById(attempt.getId()).orElseThrow().getStatus())
                .isEqualTo(AttemptStatus.STALE);
        assertThat(fx.steps.findById(step.getId()).orElseThrow().getState())
                .isEqualTo(StepState.READY); // 未推进
        assertThat(fx.workItems.findById(workItem.getId()).orElseThrow().getState())
                .isEqualTo(WorkItemState.LEASED); // 未被晚到者改动
        assertThat(fx.outbox.all()).isEmpty(); // 绝无半推进副作用
        assertThat(fx.findings.findByRunId(run.getId())).isEmpty();
    }

    @Test
    void successAdvancesAllAndPublishesTwoCommands() {
        T2Outcome result = fx.orchestrator.completeStep(completion(successOutcome(2)));

        assertThat(result).isEqualTo(T2Outcome.STEP_SUCCEEDED);
        assertThat(fx.attempts.findById(attempt.getId()).orElseThrow().getStatus())
                .isEqualTo(AttemptStatus.SUCCEEDED);
        assertThat(fx.steps.findById(step.getId()).orElseThrow().getState())
                .isEqualTo(StepState.SUCCEEDED);
        assertThat(fx.workItems.findById(workItem.getId()).orElseThrow().getState())
                .isEqualTo(WorkItemState.DONE);
        assertThat(fx.runs.findById(run.getId()).orElseThrow().getState())
                .isEqualTo(RunState.REVIEW_COMPLETE);

        // findings 落库（fingerprint 幂等挂载点）
        assertThat(fx.findings.findByRunId(run.getId())).hasSize(2);

        // outbox：CREATE_CHECK(seq1) → PUBLISH_REVIEW(seq2, REQUIRE_CONFIRMED 依赖)，同 epoch
        List<OutboxCommand> commands = fx.outbox.all();
        assertThat(commands).hasSize(2);
        OutboxCommand createCheck = commands.get(0);
        OutboxCommand publishReview = commands.get(1);
        assertThat(createCheck.commandType()).isEqualTo(CommandType.CREATE_CHECK);
        assertThat(publishReview.commandType()).isEqualTo(CommandType.PUBLISH_REVIEW);
        assertThat(createCheck.aggregateSequence()).isEqualTo(1);
        assertThat(publishReview.aggregateSequence()).isEqualTo(2);
        assertThat(publishReview.publicationEpoch()).isEqualTo(createCheck.publicationEpoch());
        assertThat(createCheck.aggregateKey()).isEqualTo("pr:12345#7");
        assertThat(fx.outbox.dependencies()).hasSize(1);
        assertThat(fx.outbox.dependencies().get(0).operationId()).isEqualTo(publishReview.operationId());
        assertThat(fx.outbox.dependencies().get(0).dependsOn()).isEqualTo(createCheck.operationId());

        // 两条 payload 均落 CAS + artifact 登记
        assertThat(fx.cas.exists(createCheck.payloadArtifactDigest())).isTrue();
        assertThat(fx.cas.exists(publishReview.payloadArtifactDigest())).isTrue();

        // 事件：STEP_RESULT + PUBLICATION_REQUESTED ×2
        assertThat(fx.events.all())
                .extracting(e -> e.eventType())
                .containsSequence(ExecutionEventType.RUN_CREATED, ExecutionEventType.STEP_RESULT,
                        ExecutionEventType.PUBLICATION_REQUESTED, ExecutionEventType.PUBLICATION_REQUESTED);
    }

    @Test
    void retryableFailureWithBudgetSchedulesRetry() {
        T2Outcome result = fx.orchestrator.completeStep(completion(
                new StepOutcome.Failed("MODEL_TIMEOUT", "TIMEOUT", "{}", true)));

        assertThat(result).isEqualTo(T2Outcome.RETRY_SCHEDULED);
        assertThat(fx.attempts.findById(attempt.getId()).orElseThrow().getStatus())
                .isEqualTo(AttemptStatus.FAILED_RETRYABLE);
        WorkItem wi = fx.workItems.findById(workItem.getId()).orElseThrow();
        assertThat(wi.getState()).isEqualTo(WorkItemState.RETRY_WAIT);
        assertThat(wi.getAvailableAt()).isAfter(Instant.now()); // 退避后重领
        assertThat(fx.steps.findById(step.getId()).orElseThrow().getState())
                .isEqualTo(StepState.WAITING);
        // Run 不推进、无 outbox 副作用
        assertThat(fx.runs.findById(run.getId()).orElseThrow().getState())
                .isEqualTo(RunState.CREATED);
        assertThat(fx.outbox.all()).isEmpty();
    }

    @Test
    void exhaustedBudgetFailsStepAndRun() {
        // attemptCount 已耗尽（3/3）：retryable 也直接终态
        WorkItem exhausted = new WorkItem(workItem.getId(), run.getId(), step.getId(),
                ReviewOrchestrator.WORK_TYPE_REVIEW, WorkItemState.LEASED, 0, Instant.now(),
                "worker-1", Instant.now().plusSeconds(60), workItem.getLeaseEpoch(),
                3, 3, workItem.getCreatedAt(), Instant.now());
        fx.workItems.save(exhausted);

        T2Outcome result = fx.orchestrator.completeStep(completion(
                new StepOutcome.Failed("MODEL_TIMEOUT", "TIMEOUT", "{}", true)));

        assertThat(result).isEqualTo(T2Outcome.STEP_FAILED);
        assertThat(fx.workItems.findById(workItem.getId()).orElseThrow().getState())
                .isEqualTo(WorkItemState.DEAD);
        assertThat(fx.steps.findById(step.getId()).orElseThrow().getState())
                .isEqualTo(StepState.FAILED);
        assertThat(fx.runs.findById(run.getId()).orElseThrow().getState())
                .isEqualTo(RunState.FAILED);
        assertThat(fx.outbox.all()).isEmpty();
    }

    @Test
    void terminalFailureFailsImmediately() {
        T2Outcome result = fx.orchestrator.completeStep(completion(
                new StepOutcome.Failed("SECURITY_REJECTION", "MALICIOUS_TAR", "{}", false)));

        assertThat(result).isEqualTo(T2Outcome.STEP_FAILED);
        assertThat(fx.attempts.findById(attempt.getId()).orElseThrow().getStatus())
                .isEqualTo(AttemptStatus.FAILED_TERMINAL);
        assertThat(fx.steps.findById(step.getId()).orElseThrow().getState())
                .isEqualTo(StepState.FAILED);
        assertThat(fx.runs.findById(run.getId()).orElseThrow().getState())
                .isEqualTo(RunState.FAILED);
    }
}
