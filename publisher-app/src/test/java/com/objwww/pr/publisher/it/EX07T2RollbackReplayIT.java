package com.objwww.pr.publisher.it;

import com.objwww.pr.control.application.StepCompletion;
import com.objwww.pr.control.application.StepOutcome;
import com.objwww.pr.control.application.T2Outcome;
import com.objwww.pr.control.domain.ai.TokenUsage;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.review.ReviewFindingDraft;
import com.objwww.pr.control.domain.review.ReviewOutcome;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.RunState;
import com.objwww.pr.shared.StepState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EX-07 Control 重启于 T2 提交前：事务回滚、无半状态；重放（同一租约凭据重交 T2）可重建。
 * 崩溃点用 sabotaged ArtifactRepository 模拟：T2 中途（findings/sequence 已动）登记
 * REVIEW_PAYLOAD 时抛异常，整笔必须回滚。
 */
class EX07T2RollbackReplayIT extends PostgresITBase {

    private ItHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ItHarness(casDir, null);
    }

    @Test
    void t2RollbackLeavesNoPartialStateAndReplaySucceeds() {
        ReviewRun run = harness.dispatchOpened(
                ItHarness.prEvent("ex07-d1", 3008L, "objwww/mall", 38,
                        "head" + "7".repeat(36), "opened"),
                ItTarballs.singleFile("src/A.java", "class A {}\n"), "diff");
        ItHarness.ClaimedWork claimed = harness.claimManually("worker-1");
        long eventsBefore = count("execution_event");

        StepOutcome.Succeeded success = new StepOutcome.Succeeded(Digest.sha256Of("ex07-output"),
                new ReviewOutcome(List.of(new ReviewFindingDraft("src/A.java", 1, 1,
                        "rule-1", "INFO", "msg", Digest.sha256Of("ex07-fp"))),
                        0, 0, 1, 1, 0, new TokenUsage(0, 0, 0)));
        StepCompletion completion = new StepCompletion(claimed.workItem().getId(),
                claimed.step().getId(), claimed.attempt().getId(), "worker-1", 1, success);

        // T2 提交前崩溃：整笔回滚
        harness.swapArtifactRepository(new FailOnceArtifactRepository(harness.artifactRepo));
        assertThatThrownBy(() -> harness.completeStep(completion))
                .hasMessageContaining("模拟 Control 于 T2 提交前崩溃");

        // 无半状态：findings/outbox/sequence/事件全回滚；work_item 仍 LEASED、step 仍 READY
        assertThat(count("review_finding")).isZero();
        assertThat(count("outbox_command")).isZero();
        assertThat(count("outbox_dependency")).isZero();
        assertThat(count("execution_event")).isEqualTo(eventsBefore);
        assertThat(harness.stepRepo.findById(claimed.step().getId()).orElseThrow().getState())
                .isEqualTo(StepState.READY);
        assertThat(harness.workItemRepo.findById(claimed.workItem().getId()).orElseThrow()
                .getState().name()).isEqualTo("LEASED");
        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(3008L, 38)
                .orElseThrow().getId();
        assertThat(subjectCursor(subjectId)[1]).isEqualTo(1); // next_outbox_sequence 未被消耗

        // 重放：同一租约凭据重交 T2 → 正常完成
        harness.restoreArtifactRepository();
        T2Outcome outcome = harness.completeStep(completion);
        assertThat(outcome).isEqualTo(T2Outcome.STEP_SUCCEEDED);
        assertThat(count("review_finding")).isEqualTo(1);
        assertThat(count("outbox_command")).isEqualTo(2);
        assertThat(subjectCursor(subjectId)[1]).isEqualTo(3);
        assertThat(harness.runRepo.findById(run.getId()).orElseThrow().getState())
                .isEqualTo(RunState.REVIEW_COMPLETE);
    }
}
