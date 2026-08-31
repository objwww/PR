package com.objwww.pr.publisher.it;

import com.objwww.pr.control.application.OutboxWriter;
import com.objwww.pr.control.application.PublicationRequest;
import com.objwww.pr.control.application.StepCompletion;
import com.objwww.pr.control.application.StepOutcome;
import com.objwww.pr.control.application.T2Outcome;
import com.objwww.pr.control.domain.ai.TokenUsage;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.review.ReviewFindingDraft;
import com.objwww.pr.control.domain.review.ReviewOutcome;
import com.objwww.pr.control.infrastructure.persistence.PostgresOutboxCommandRepository;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.IllegalTransitionException;
import com.objwww.pr.shared.OperationId;
import com.objwww.pr.shared.RunState;
import com.objwww.pr.shared.StepState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EX-07 Control 重启于 T2 提交前：事务回滚、无半状态；重放（同一租约凭据重交 T2）可重建。
 * 崩溃点用 sabotaged ArtifactRepository 模拟：T2 中途（findings/sequence 已动）登记
 * REVIEW_PAYLOAD 时抛异常，整笔必须回滚。
 *
 * <p>补两个"T2 提交后"窗口（评审对账缺口）：
 * <ul>
 *   <li>{@link #t2ReplayAfterCommitProducesNoSecondOutboxCommand}：T2 已成功提交后
 *       同一租约凭据整体重放——不得产生第二条 Outbox；</li>
 *   <li>{@link #sameOperationIdReinsertHitsPrimaryKeyAndRollsBack}：operation_id 主键
 *       兜底——同 operation_id 二次插入被拒且整笔回滚。</li>
 * </ul>
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
                        0, 0, 1, 1, 0, new TokenUsage(0, 0, 0), "model-response-raw"));
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

    /**
     * EX-07 补（崩溃点在 T2 成功提交之后）：Worker 以同一租约凭据整体重放同一 completion。
     * 实测行为：work_item 已 DONE → 租约栅栏 0 行进 STALE 分支，但 attempt 已是终态
     * SUCCEEDED，AttemptStatusMachine 拒绝 SUCCEEDED→STALE，抛 IllegalTransitionException，
     * 事务整笔回滚——不产生第二条 outbox。
     * 注意：重放未被静默吸收为 STALE_IGNORED，而是以未吸收异常收场（已记入评审对账报告）；
     * "恰好一次"由整笔回滚 + operation_id 主键兜底（下一条用例）。
     */
    @Test
    void t2ReplayAfterCommitProducesNoSecondOutboxCommand() {
        ReviewRun run = harness.dispatchOpened(
                ItHarness.prEvent("ex07-d2", 3009L, "objwww/mall", 39,
                        "head" + "8".repeat(36), "opened"),
                ItTarballs.singleFile("src/A.java", "class A {}\n"), "diff");
        ItHarness.ClaimedWork claimed = harness.claimManually("worker-1");

        StepOutcome.Succeeded success = new StepOutcome.Succeeded(Digest.sha256Of("ex07-output2"),
                new ReviewOutcome(List.of(new ReviewFindingDraft("src/A.java", 1, 1,
                        "rule-1", "INFO", "msg", Digest.sha256Of("ex07-fp2"))),
                        0, 0, 1, 1, 0, new TokenUsage(0, 0, 0), "model-response-raw"));
        StepCompletion completion = new StepCompletion(claimed.workItem().getId(),
                claimed.step().getId(), claimed.attempt().getId(), "worker-1", 1, success);

        // 首次 T2 正常提交：outbox 两行（CREATE_CHECK + PUBLISH_REVIEW）、finding 一行
        assertThat(harness.completeStep(completion)).isEqualTo(T2Outcome.STEP_SUCCEEDED);
        assertThat(count("outbox_command")).isEqualTo(2);
        assertThat(count("review_finding")).isEqualTo(1);
        assertThat(harness.runRepo.findById(run.getId()).orElseThrow().getState())
                .isEqualTo(RunState.REVIEW_COMPLETE);
        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(3009L, 39)
                .orElseThrow().getId();
        long[] cursorBefore = subjectCursor(subjectId);
        long eventsBefore = count("execution_event");

        // T2 提交后崩溃窗口：同一租约凭据重放（当前实现抛 IllegalTransitionException 收场）
        assertThatThrownBy(() -> harness.completeStep(completion))
                .isInstanceOf(IllegalTransitionException.class);

        // 恰好一次兜底成立：整笔回滚——无第二条 outbox、无重复 finding、游标与账本不动
        assertThat(count("outbox_command")).isEqualTo(2);
        assertThat(count("review_finding")).isEqualTo(1);
        assertThat(count("execution_event")).isEqualTo(eventsBefore);
        assertThat(subjectCursor(subjectId)).isEqualTo(cursorBefore);
    }

    /**
     * EX-07 补（operation_id 唯一约束兜底）：operation_id 是 outbox_command 主键，
     * 仓储刻意不加 ON CONFLICT——同 operation_id 二次 requestPublication 撞主键
     * （DuplicateKeyException）且整笔回滚，表内仍恰好 1 行，sequence 消耗随回滚归还。
     */
    @Test
    void sameOperationIdReinsertHitsPrimaryKeyAndRollsBack() {
        ReviewRun run = harness.dispatchOpened(
                ItHarness.prEvent("ex07-d3", 3010L, "objwww/mall", 40,
                        "head" + "9".repeat(36), "opened"),
                ItTarballs.singleFile("src/A.java", "class A {}\n"), "diff");
        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(3010L, 40)
                .orElseThrow().getId();

        OutboxWriter writer = new OutboxWriter(new PostgresOutboxCommandRepository(controlJdbc),
                harness.sequenceAllocator, harness.casStore, harness.artifactRepo);
        OperationId opId = OperationId.random();
        PublicationRequest request = new PublicationRequest(opId, subjectId, run.getId(),
                run.getPrRevisionId(), "pr:3010#40", CommandType.CREATE_CHECK, ItHarness.POLICY,
                "{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8), List.of());

        controlTx.executeWithoutResult(status -> writer.requestPublication(request));
        assertThat(count("outbox_command")).isEqualTo(1);
        assertThat(subjectCursor(subjectId)[1]).isEqualTo(2); // sequence 1 已消耗

        // 同 operation_id 二次插入：主键冲突 → 异常上抛 + 回滚（不静默跳过、不产生第二行）
        assertThatThrownBy(() -> controlTx.executeWithoutResult(
                status -> writer.requestPublication(request)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(count("outbox_command")).isEqualTo(1);
        assertThat(subjectCursor(subjectId)[1]).isEqualTo(2); // 第二笔的 sequence 消耗已回滚归还
    }
}
