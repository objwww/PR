package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.model.PrSubjectState;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.model.RunStep;
import com.objwww.pr.control.domain.model.WorkItem;
import com.objwww.pr.control.support.OrchestratorFixture;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.RunState;
import com.objwww.pr.shared.WorkItemState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T06 事务脚本单测（方案 §4.4，内存假实现）：
 * draft 廉价预检（I11：零 Run 零 Outbox 零事件）/ T-close / T-draft（I15：epoch+1 +
 * 在途 Run SUPERSEDED + 账本事件，同事务）/ 重放幂等（不重复 bump）/ 水印推进（CT-14
 * 语义：只增不回退；EX-18：null 不覆盖）。
 */
class ReviewOrchestratorT06Test {

    private static final String POLICY = "m1-policy-v1";
    private static final Instant T1 = Instant.parse("2025-06-01T12:00:00Z");
    private static final Instant T2 = Instant.parse("2025-06-01T12:00:01Z");
    private static final Instant T0 = Instant.parse("2025-06-01T11:59:59Z");
    private static final Instant T3 = Instant.parse("2025-06-01T12:00:02Z");

    private OrchestratorFixture fx;

    @BeforeEach
    void setUp() {
        fx = new OrchestratorFixture();
    }

    private ReviewRun givenActiveRun() {
        return fx.orchestrator.runIntake(new IntakeCommand(987L, 12345L, "org/repo", 7,
                PrSubjectState.OPEN, false, false, "head1", "main", "base1", null,
                Digest.sha256Of("diff-head1"), Digest.sha256Of("snap-head1"),
                POLICY, "m1-prompt-v1", "m1-toolset-v1", "seed-1", null));
    }

    private static ProjectionSyncCommand draftSync(Instant updatedAt) {
        return new ProjectionSyncCommand(987L, 12345L, "org/repo", 7,
                PrSubjectState.OPEN, true, false, POLICY, updatedAt);
    }

    private static ProjectionSyncCommand closeSync(boolean merged, Instant updatedAt) {
        return new ProjectionSyncCommand(987L, 12345L, "org/repo", 7,
                PrSubjectState.CLOSED, false, merged, POLICY, updatedAt);
    }

    private static ProjectionSyncCommand reopenSync(Instant updatedAt) {
        return new ProjectionSyncCommand(987L, 12345L, "org/repo", 7,
                PrSubjectState.OPEN, false, false, POLICY, updatedAt);
    }

    private UUID subjectId() {
        return fx.subjects.findByRepositoryAndPrNumber(12345L, 7).orElseThrow().getId();
    }

    // ------------------------------------------------------------------ draft 廉价预检（I11/ST-12 单元侧）

    @Test
    void draftPrecheckCreatesProjectionOnlyWithZeroRunZeroOutbox() {
        fx.orchestrator.applyDraftPrecheck(draftSync(T1));

        var subject = fx.subjects.findByRepositoryAndPrNumber(12345L, 7).orElseThrow();
        assertThat(subject.getState()).isEqualTo(PrSubjectState.OPEN);
        assertThat(subject.isDraft()).isTrue();
        assertThat(subject.getPublicationEpoch()).isZero(); // 预检不换届（epoch bump 只属于 T-draft/T-close/reopened）
        assertThat(subject.getLastEventUpdatedAt()).isEqualTo(T1); // 水印推进
        // I11：零 Run / 零 Outbox / 零账本事件 / 零 Revision
        assertThat(fx.runs.findActiveByPrSubjectId(subject.getId())).isEmpty();
        assertThat(fx.outbox.all()).isEmpty();
        assertThat(fx.events.all()).isEmpty();
        assertThat(fx.revisions.findById(subject.getCurrentRevisionId() == null
                ? UUID.randomUUID() : subject.getCurrentRevisionId())).isEmpty();
    }

    @Test
    void draftPrecheckWithNullUpdatedAtDoesNotTouchWatermark() {
        // EX-18：远端缺 updated_at → 水印不覆盖（保持 NULL）
        fx.orchestrator.applyDraftPrecheck(draftSync(null));

        assertThat(fx.subjects.findByRepositoryAndPrNumber(12345L, 7).orElseThrow()
                .getLastEventUpdatedAt()).isNull();
    }

    @Test
    void watermarkOnlyAdvancesNeverRegresses() {
        // CT-14 语义（内存侧）：GREATEST——旧值不覆新值
        fx.orchestrator.applyDraftPrecheck(draftSync(T2));
        fx.orchestrator.applyDraftPrecheck(draftSync(T0)); // 更旧的水印值

        assertThat(fx.subjects.findByRepositoryAndPrNumber(12345L, 7).orElseThrow()
                .getLastEventUpdatedAt()).isEqualTo(T2);
    }

    // ------------------------------------------------------------------ T-close（I15/ST-13 单元侧）

    @Test
    void closeGenerationBumpsEpochAndSupersedesActiveRunAtomically() {
        ReviewRun run = givenActiveRun();
        RunStep step = fx.steps.findByRunId(run.getId()).get(0);
        long epochBefore = fx.subjects.findById(subjectId()).orElseThrow().getPublicationEpoch();

        fx.orchestrator.closeGeneration(closeSync(true, T2));

        var subject = fx.subjects.findById(subjectId()).orElseThrow();
        assertThat(subject.getState()).isEqualTo(PrSubjectState.CLOSED);
        assertThat(subject.isMerged()).isTrue();
        assertThat(subject.getPublicationEpoch()).isEqualTo(epochBefore + 1); // I15：必递增
        assertThat(subject.getLastEventUpdatedAt()).isEqualTo(T2);

        // 在途 Run SUPERSEDED + 未完成 WorkItem CANCELLED + REVISION_INVALIDATED 落账
        assertThat(fx.runs.findById(run.getId()).orElseThrow().getState())
                .isEqualTo(RunState.SUPERSEDED);
        assertThat(fx.workItems.findByStepId(step.getId()).orElseThrow().getState())
                .isEqualTo(WorkItemState.CANCELLED);
        assertThat(fx.events.all().stream()
                .filter(e -> e.eventType() == ExecutionEventType.REVISION_INVALIDATED))
                .anyMatch(e -> e.reviewRunId().equals(run.getId())
                        && e.payload().get("reason").equals("PR_CLOSED")
                        && e.payload().get("publication_epoch").equals(epochBefore + 1));
        // Control 不动 outbox：零新命令（旧 PENDING 由 Publisher sweep 级联，ST-19）
        assertThat(fx.outbox.all()).isEmpty();
    }

    @Test
    void closeGenerationReplayDoesNotDoubleBump() {
        // 崩溃重放/重投幂等：已 CLOSED 且无在途 Run → 不重复 bump epoch
        givenActiveRun();
        fx.orchestrator.closeGeneration(closeSync(false, T2));
        long epochAfterClose = fx.subjects.findById(subjectId()).orElseThrow().getPublicationEpoch();
        long eventsAfterClose = fx.events.all().size();

        fx.orchestrator.closeGeneration(closeSync(false, T2));

        var subject = fx.subjects.findById(subjectId()).orElseThrow();
        assertThat(subject.getPublicationEpoch()).isEqualTo(epochAfterClose);
        assertThat(fx.events.all()).hasSize((int) eventsAfterClose); // 零新增事件
    }

    @Test
    void closeGenerationForNeverReviewedPrCreatesClosedProjection() {
        // closed 事件先于任何评审到达（权威读确认 closed）：建最小投影行，无 Run
        fx.orchestrator.closeGeneration(closeSync(false, T1));

        var subject = fx.subjects.findByRepositoryAndPrNumber(12345L, 7).orElseThrow();
        assertThat(subject.getState()).isEqualTo(PrSubjectState.CLOSED);
        assertThat(fx.runs.findActiveByPrSubjectId(subject.getId())).isEmpty();
        assertThat(fx.events.all()).isEmpty(); // 无 Run 可挂事件（V1 FK 约束，见类注释）
    }

    // ------------------------------------------------------------------ T-draft（I15/ST-19 单元侧）

    @Test
    void convertToDraftBumpsEpochAndSupersedesActiveRun() {
        ReviewRun run = givenActiveRun();

        fx.orchestrator.convertToDraftGeneration(draftSync(T2));

        var subject = fx.subjects.findById(subjectId()).orElseThrow();
        assertThat(subject.isDraft()).isTrue();
        assertThat(subject.getState()).isEqualTo(PrSubjectState.OPEN);
        assertThat(subject.getPublicationEpoch()).isEqualTo(2); // T1 首次换届 0→1，T-draft 1→2
        assertThat(fx.runs.findById(run.getId()).orElseThrow().getState())
                .isEqualTo(RunState.SUPERSEDED);
        assertThat(fx.events.all().stream()
                .filter(e -> e.eventType() == ExecutionEventType.REVISION_INVALIDATED))
                .anyMatch(e -> e.payload().get("reason").equals("CONVERTED_TO_DRAFT"));
    }

    @Test
    void convertToDraftReplayDoesNotDoubleBump() {
        givenActiveRun();
        fx.orchestrator.convertToDraftGeneration(draftSync(T2));
        long epochAfterDraft = fx.subjects.findById(subjectId()).orElseThrow().getPublicationEpoch();

        fx.orchestrator.convertToDraftGeneration(draftSync(T2));

        assertThat(fx.subjects.findById(subjectId()).orElseThrow().getPublicationEpoch())
                .isEqualTo(epochAfterDraft);
    }

    // ------------------------------------------------------------------ T-reopen（I15/ST-20，INC-26）

    @Test
    void reopenGenerationBumpsEpochEvenWhenRevisionUnchanged() {
        // ST-20 核心：close（epoch 1→2）后 reopen，代码未变（不再走 T1 换届判定）也必递增
        ReviewRun run = givenActiveRun(); // T1 首次换届 0→1
        fx.orchestrator.closeGeneration(closeSync(false, T2)); // 1→2
        long epochAfterClose = fx.subjects.findById(subjectId()).orElseThrow().getPublicationEpoch();

        fx.orchestrator.reopenGeneration(reopenSync(T3));

        var subject = fx.subjects.findById(subjectId()).orElseThrow();
        assertThat(subject.getState()).isEqualTo(PrSubjectState.OPEN);
        assertThat(subject.isDraft()).isFalse();
        assertThat(subject.getPublicationEpoch()).isEqualTo(epochAfterClose + 1); // I15：必递增
        assertThat(subject.getLastEventUpdatedAt()).isEqualTo(T3);
        // 新 Run 的创建不归 T-reopen（由随后的全量 T1 负责），此处只换届；
        // 无在途 Run 可挂 → 零账本事件（V1 FK 约束，同 closeGenerationForNeverReviewed 注释）
        assertThat(fx.runs.findActiveByPrSubjectId(subject.getId())).isEmpty();
    }

    @Test
    void reopenGenerationReplayDoesNotDoubleBump() {
        // 崩溃重放/duplicate reopened 幂等：已 OPEN 非 draft → 不重复 bump
        givenActiveRun();
        fx.orchestrator.closeGeneration(closeSync(false, T2));
        fx.orchestrator.reopenGeneration(reopenSync(T3));
        long epochAfterReopen = fx.subjects.findById(subjectId()).orElseThrow().getPublicationEpoch();
        long eventsAfterReopen = fx.events.all().size();

        fx.orchestrator.reopenGeneration(reopenSync(T3));

        assertThat(fx.subjects.findById(subjectId()).orElseThrow().getPublicationEpoch())
                .isEqualTo(epochAfterReopen);
        assertThat(fx.events.all()).hasSize((int) eventsAfterReopen); // 零新增事件
    }

    // ------------------------------------------------------------------ T1 水印（CT-14 链路侧）

    @Test
    void t1AdvancesWatermarkWithRemoteUpdatedAt() {
        fx.orchestrator.runIntake(new IntakeCommand(987L, 12345L, "org/repo", 7,
                PrSubjectState.OPEN, false, false, "head1", "main", "base1", null,
                Digest.sha256Of("diff-head1"), Digest.sha256Of("snap-head1"),
                POLICY, "m1-prompt-v1", "m1-toolset-v1", "d-1", T1));

        assertThat(fx.subjects.findByRepositoryAndPrNumber(12345L, 7).orElseThrow()
                .getLastEventUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void t1WithNullUpdatedAtLeavesWatermarkNull() {
        // EX-18：T1 成功但远端缺 updated_at → 不造水印
        givenActiveRun();

        assertThat(fx.subjects.findByRepositoryAndPrNumber(12345L, 7).orElseThrow()
                .getLastEventUpdatedAt()).isNull();
    }
}
