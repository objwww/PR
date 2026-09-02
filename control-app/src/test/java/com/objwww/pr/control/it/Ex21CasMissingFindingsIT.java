package com.objwww.pr.control.it;

import com.objwww.pr.control.application.StepOutcome;
import com.objwww.pr.control.application.WorkItemWorker;
import com.objwww.pr.control.domain.ai.MockModelGateway;
import com.objwww.pr.control.domain.model.StepCheckpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-21（方案 §11 L4 表，回指 I18）——checkpoint 的 findings digest 被删（CAS 损坏/丢失）：
 * 复用判定 fail-closed，整体丢弃重跑，记 DISCARDED(CAS_MISSING_FINDINGS)。
 *
 * <p>场景（注入）：attempt#1 完整写 checkpoint（模型 + 双 CAS + 行）后崩溃（不 T2）；
 * 直接从 CAS 目录删除 findings blob（登记行仍在——环境性缺失走 CAS_MISSING_* 而非
 * CHECKPOINT_CORRUPT，§4.2 复用判定条件 2）；租约到期后新进程 runOnce。
 *
 * <p>预期断言：CHECKPOINT_DISCARDED 恰 1 条且 payload.reason = CAS_MISSING_FINDINGS；
 * 零 CHECKPOINT_REUSED；模型计数 2；重跑后 checkpoint 被新 attempt 覆盖（attempt_no=2）
 * 且终态正常闭环。
 *
 * <p>取证：execution_event(payload.reason) / 模型计数 / step_checkpoint。
 */
class Ex21CasMissingFindingsIT extends PostgresITBase {

    @TempDir
    Path casDir;

    private StCheckpointHarness h;

    @BeforeEach
    void setUp() {
        h = new StCheckpointHarness(casDir);
    }

    @Test
    void missingFindingsBlobDiscardsCheckpointAndReruns() throws Exception {
        StCheckpointHarness.Seed seed = h.seedFirstRun(111, "head-ex21", StCheckpointHarness.PROMPT_V1);
        MockModelGateway model = StCheckpointHarness.modelReturningOutput();

        // attempt#1 完整写入后崩溃（T2 前窗口）
        StCheckpointHarness.Claimed first = h.claim("ex21-worker-a");
        StepOutcome outcome = h.newReviewExecutor(model).execute(first.context(), () -> true);
        assertThat(outcome).isInstanceOf(StepOutcome.Succeeded.class);
        assertThat(count("step_checkpoint")).isEqualTo(1);

        // CAS 损坏注入：findings blob 被删（登记行保留）
        StepCheckpoint checkpoint = h.checkpointRepo.find(seed.stepId(), StepCheckpoint.REVIEW_OUTCOME)
                .orElseThrow();
        String findingsDigest = checkpoint.outputArtifactDigest().value();
        Files.delete(casDir.resolve(findingsDigest.substring(0, 2)).resolve(findingsDigest));
        assertThat(h.cas.exists(new com.objwww.pr.shared.Digest(findingsDigest))).isFalse();

        // 接管重跑：fail-closed 丢弃 → 重调模型 → 正常闭环
        h.forceLeaseExpired(seed.workItemId());
        model.enqueueContent(StCheckpointHarness.MODEL_OUTPUT);
        WorkItemWorker workerB = h.newWorker("ex21-worker-b", model);
        workerB.runOnce();

        assertThat(model.requests()).as("CAS 缺 findings → 重调模型").hasSize(2);
        assertThat(h.eventCount(seed.runId(), "CHECKPOINT_DISCARDED")).isEqualTo(1);
        assertThat(h.lastDiscardReason(seed.runId())).isEqualTo("CAS_MISSING_FINDINGS");
        assertThat(h.eventCount(seed.runId(), "CHECKPOINT_REUSED")).isZero();
        assertThat(h.checkpointRepo.find(seed.stepId(), StepCheckpoint.REVIEW_OUTCOME)
                .orElseThrow().attemptNo()).as("checkpoint 被重跑的 attempt 覆盖").isEqualTo(2);
        assertThat(h.stepState(seed.stepId())).isEqualTo("SUCCEEDED");
        assertThat(h.runState(seed.runId())).isEqualTo("REVIEW_COMPLETE");
        assertThat(count("review_finding")).isEqualTo(1);
        assertThat(count("outbox_command")).isEqualTo(2);
    }
}
