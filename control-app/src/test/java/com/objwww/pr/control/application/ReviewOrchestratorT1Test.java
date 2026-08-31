package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.model.PRSubject;
import com.objwww.pr.control.domain.model.PrSubjectState;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.model.RunStep;
import com.objwww.pr.control.domain.model.WorkItem;
import com.objwww.pr.control.support.OrchestratorFixture;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.RunState;
import com.objwww.pr.shared.StepState;
import com.objwww.pr.shared.WorkItemState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1 事务脚本（§6.1 逐条对照）：upsert subject → fingerprint 复用/插入 revision（I12）
 * → 换届（epoch+1 + 旧 Run SUPERSEDED + WorkItem CANCELLED，同事务）→ Run+Step+WorkItem → 事件。
 * B-3：run_key 唯一冲突上抛，findExistingRun 幂等回读。
 */
class ReviewOrchestratorT1Test {

    private OrchestratorFixture fx;

    @BeforeEach
    void setUp() {
        fx = new OrchestratorFixture();
    }

    private static IntakeCommand command(String headSha, String deliveryId) {
        return new IntakeCommand(987L, 12345L, "org/repo", 7,
                PrSubjectState.OPEN, false, false,
                headSha, "main", "basesha456", null,
                Digest.sha256Of("diff-" + headSha), Digest.sha256Of("snap-" + headSha),
                "m0-policy-v1", "m0-prompt-v1", "m0-toolset-v1", deliveryId, null);
    }

    @Test
    void firstIntakeCreatesSubjectRevisionRunStepWorkItem() {
        ReviewRun run = fx.orchestrator.runIntake(command("head1", "d-1"));

        // subject：投影字段 + epoch 账户行（首次挂 revision 即 epoch 0→1，见类注释"换届"语义）
        PRSubject subject = fx.subjects.findByRepositoryAndPrNumber(12345L, 7).orElseThrow();
        assertThat(subject.getRepositoryFullName()).isEqualTo("org/repo");
        assertThat(subject.getPublicationEpoch()).isEqualTo(1);
        assertThat(subject.getNextOutboxSequence()).isEqualTo(1); // sequence 不经 T1 消耗
        assertThat(subject.getCurrentRevisionId()).isEqualTo(run.getPrRevisionId());

        // revision：digest 完整插入（I12）
        var revision = fx.revisions.findById(run.getPrRevisionId()).orElseThrow();
        assertThat(revision.getHeadSha()).isEqualTo("head1");
        assertThat(revision.getDiffDigest()).isEqualTo(Digest.sha256Of("diff-head1"));

        // run：CREATED + root=self（E9 lineage）
        assertThat(run.getState()).isEqualTo(RunState.CREATED);
        assertThat(run.getRootRunId()).isEqualTo(run.getId());
        assertThat(run.getParentRunId()).isNull();

        // 首个 step（REVIEW/READY）+ work item（READY）
        List<RunStep> steps = fx.steps.findByRunId(run.getId());
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getStepType()).isEqualTo(ReviewOrchestrator.STEP_TYPE_REVIEW);
        assertThat(steps.get(0).getState()).isEqualTo(StepState.READY);
        WorkItem wi = fx.workItems.findByStepId(steps.get(0).getId()).orElseThrow();
        assertThat(wi.getState()).isEqualTo(WorkItemState.READY);

        // 事件：首次挂 revision 属换届（REVISION_INVALIDATED 无旧 run 可 supersede，不记）+ RUN_CREATED
        assertThat(fx.events.all())
                .extracting(e -> e.eventType())
                .containsExactly(ExecutionEventType.RUN_CREATED);
    }

    @Test
    void redeliveryReusesRevisionAndRunKeyConflicts() {
        fx.orchestrator.runIntake(command("head1", "d-1"));
        long epochAfterFirst = fx.subjects.findByRepositoryAndPrNumber(12345L, 7).orElseThrow()
                .getPublicationEpoch();

        // B-3/ST-05：同 delivery 重投 → 同 fingerprint 复用 revision（I12，不新插）→ run_key 冲突上抛
        assertThatThrownBy(() -> fx.orchestrator.runIntake(command("head1", "d-1")))
                .isInstanceOf(DuplicateKeyException.class);

        // revision 复用：仍只有一行；epoch 未再 bump（revision/policy 均未变）
        PRSubject subject = fx.subjects.findByRepositoryAndPrNumber(12345L, 7).orElseThrow();
        assertThat(fx.revisions.findByFingerprint(subject.getId(),
                fx.revisionService.revisionFingerprint(12345L, 7, "head1", "basesha456", null,
                        Digest.sha256Of("diff-head1")))).isPresent();
        assertThat(subject.getPublicationEpoch()).isEqualTo(epochAfterFirst);

        // 幂等回读：能找到既有 Run（IntakeService 的 B-3 捕获路径）
        assertThat(fx.orchestrator.findExistingRun(command("head1", "d-1"))).isPresent();
    }

    @Test
    void synchronizeSupersedeOldGenerationAtomically() {
        ReviewRun oldRun = fx.orchestrator.runIntake(command("head1", "d-1"));
        UUID oldRevisionId = oldRun.getPrRevisionId();
        RunStep oldStep = fx.steps.findByRunId(oldRun.getId()).get(0);

        // push 新 commit：新 revision → 换届
        ReviewRun newRun = fx.orchestrator.runIntake(command("head2", "d-2"));

        // 旧 Run SUPERSEDED + 其未完成 WorkItem CANCELLED（同事务语义由内存提交点模拟）
        assertThat(fx.runs.findById(oldRun.getId()).orElseThrow().getState())
                .isEqualTo(RunState.SUPERSEDED);
        assertThat(fx.workItems.findByStepId(oldStep.getId()).orElseThrow().getState())
                .isEqualTo(WorkItemState.CANCELLED);

        // epoch+1 + current_revision 切换
        PRSubject subject = fx.subjects.findByRepositoryAndPrNumber(12345L, 7).orElseThrow();
        assertThat(subject.getPublicationEpoch()).isEqualTo(2);
        assertThat(subject.getCurrentRevisionId()).isEqualTo(newRun.getPrRevisionId());
        assertThat(newRun.getPrRevisionId()).isNotEqualTo(oldRevisionId);

        // 事件：REVISION_INVALIDATED 挂被作废旧 Run 自己的流（correlation 指回新 run，
        // fold 旧 run 可投影出 SUPERSEDED）；新 run 流首事件必须是 RUN_CREATED（T17/ST-02 实证修正）
        var invalidated = fx.events.all().stream()
                .filter(e -> e.eventType() == ExecutionEventType.REVISION_INVALIDATED)
                .reduce((a, b) -> b).orElseThrow();
        assertThat(invalidated.reviewRunId()).isEqualTo(oldRun.getId());
        assertThat(invalidated.correlationId()).isEqualTo(newRun.getId());
        assertThat(invalidated.payload()).containsEntry("new_run_id", newRun.getId().toString());
        // 新 run 流：RUN_CREATED 为首事件（Projector 流不变量）
        assertThat(fx.events.all().stream()
                        .filter(e -> e.reviewRunId().equals(newRun.getId())).findFirst().orElseThrow()
                        .eventType())
                .isEqualTo(ExecutionEventType.RUN_CREATED);
    }

    @Test
    void policyChangeBumpsEpochWithoutNewRevision() {
        ReviewRun first = fx.orchestrator.runIntake(command("head1", "d-1"));

        // policy 版本变化：revision fingerprint 不含 policy（v2.2 §3，UT-01）→ 复用同 revision 行
        IntakeCommand policyChanged = new IntakeCommand(987L, 12345L, "org/repo", 7,
                PrSubjectState.OPEN, false, false,
                "head1", "main", "basesha456", null,
                Digest.sha256Of("diff-head1"), Digest.sha256Of("snap-head1"),
                "m0-policy-v2", "m0-prompt-v1", "m0-toolset-v1", "d-3", null);
        ReviewRun second = fx.orchestrator.runIntake(policyChanged);

        assertThat(second.getPrRevisionId()).isEqualTo(first.getPrRevisionId()); // 同 revision
        assertThat(fx.runs.findById(first.getId()).orElseThrow().getState())
                .isEqualTo(RunState.SUPERSEDED); // 但旧 policy 世代作废
        PRSubject subject = fx.subjects.findByRepositoryAndPrNumber(12345L, 7).orElseThrow();
        assertThat(subject.getPublicationEpoch()).isEqualTo(2);
        assertThat(subject.getCurrentPolicyVersion()).isEqualTo("m0-policy-v2");
    }
}
