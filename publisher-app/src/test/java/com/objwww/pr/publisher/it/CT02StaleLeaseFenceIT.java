package com.objwww.pr.publisher.it;

import com.objwww.pr.control.application.StepCompletion;
import com.objwww.pr.control.application.StepOutcome;
import com.objwww.pr.control.application.T2Outcome;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.model.StepAttempt;
import com.objwww.pr.control.domain.model.WorkItem;
import com.objwww.pr.shared.AttemptStatus;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.StepState;
import com.objwww.pr.shared.WorkItemState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CT-02 晚到结果栅栏（I11/B-2）：租约过期 → 他 worker 重领（epoch+1）
 * → 旧 worker 提交 UPDATE 0 行 → Attempt 记 STALE，Step 不推进。
 */
class CT02StaleLeaseFenceIT extends PostgresITBase {

    private ItHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ItHarness(casDir, null);
    }

    @Test
    void lateCompletionIsFencedAndMarkedStale() {
        harness.runIntakeDirect(ItHarness.prEvent("ct02-d1", 1002L, "objwww/mall", 8,
                "head" + "2".repeat(36), "opened"),
                Digest.sha256Of("ct02-diff"), Digest.sha256Of("ct02-snapshot"));

        // worker-a 领取（LEASED epoch1）并记 STARTED attempt#1，随后"卡住"不提交 T2
        ItHarness.ClaimedWork byA = harness.claimManually("worker-a");
        assertThat(byA.workItem().getState()).isEqualTo(WorkItemState.LEASED);
        assertThat(byA.workItem().getLeaseEpoch()).isEqualTo(1);

        // 时间推移：租约过期（测试动作，admin 角色直接拨 lease_until）
        adminJdbc.sql("UPDATE work_item SET lease_until = :past WHERE id = :id")
                .param("past", Timestamp.from(Instant.now().minusSeconds(5)))
                .param("id", byA.workItem().getId())
                .update();

        // 恢复扫描回收（预算未尽 → READY；回收本身 epoch+1 栅栏旧 worker）；worker-b 重领（再 +1）
        boolean reclaimed = harness.orchestrator().reclaimExpiredLease(byA.workItem().getId());
        assertThat(reclaimed).isTrue();
        WorkItem reclaimedItem = harness.workItemRepo.findById(byA.workItem().getId()).orElseThrow();
        assertThat(reclaimedItem.getState()).isEqualTo(WorkItemState.READY);

        ItHarness.ClaimedWork byB = harness.claimManually("worker-b");
        assertThat(byB.workItem().getLeaseEpoch()).isEqualTo(3); // 回收 1→2、重领 2→3
        assertThat(byB.workItem().getAttemptCount()).isEqualTo(2);

        // 旧 worker-a 的晚到结果：T2 栅栏 UPDATE 0 行 → STALE_IGNORED
        long eventsBefore = count("execution_event");
        T2Outcome outcome = harness.completeStep(new StepCompletion(
                byA.workItem().getId(), byA.step().getId(), byA.attempt().getId(),
                "worker-a", 1, new StepOutcome.Failed("Late", "LATE_RESULT", "晚到", true)));
        assertThat(outcome).isEqualTo(T2Outcome.STALE_IGNORED);

        // attempt#1 记 STALE；Step 不推进（仍 READY）；run 不动；账本不追加
        StepAttempt staleAttempt = harness.attemptRepo.findById(byA.attempt().getId()).orElseThrow();
        assertThat(staleAttempt.getStatus()).isEqualTo(AttemptStatus.STALE);
        assertThat(harness.stepRepo.findById(byA.step().getId()).orElseThrow().getState())
                .isEqualTo(StepState.READY);
        assertThat(harness.workItemRepo.findById(byA.workItem().getId()).orElseThrow().getState())
                .isEqualTo(WorkItemState.LEASED); // worker-b 的租约不受影响
        assertThat(count("execution_event")).isEqualTo(eventsBefore);

        // 新 worker-b 的正常结果仍被接受（栅栏只挡旧 epoch）
        ReviewRun run = harness.runRepo.findById(byB.step().getReviewRunId()).orElseThrow();
        assertThat(run.getState().name()).isEqualTo("CREATED");
    }
}
