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
 * ST-25（方案 §11 L3 表，回指 §4.2/I18）——只写一个 CAS artifact 后崩溃：
 * 半成品不得被部分复用，下一 attempt 整体重跑。
 *
 * <p>两个互补子场景（方案表头注入方式"只写一个 CAS artifact 后崩溃"的两种残局）：
 * <ul>
 *   <li><b>25a 崩溃在两个 CAS put 之间</b>（{@link StCheckpointCrashArtifactStore}
 *       第 2 次 put 抛 SimulatedCrash）：findings blob 成孤儿、无 artifact 登记行、
 *       无 checkpoint 行 → 续跑无任何可复用锚点，整体重调模型（断言无 REUSED 事件，
 *       孤儿 blob 被同 digest 幂等覆盖）；</li>
 *   <li><b>25b checkpoint 元数据在、model response blob 缺</b>（崩溃残局的另一种收敛：
 *       行已存但其中一个 CAS 对象没活下来，直接删 blob 模拟）→ ResumeService
 *       fail-closed 丢弃：DISCARDED(CAS_MISSING_MODEL_RESPONSE) + 重调模型。</li>
 * </ul>
 *
 * <p>预期断言：模型计数 2；25a 无 CHECKPOINT_REUSED/DISCARDED（无行可丢弃）；
 * 25b execution_event payload.reason = CAS_MISSING_MODEL_RESPONSE；两路终态都正常闭环。
 *
 * <p>取证：step_checkpoint / artifact / execution_event(payload.reason) / 模型计数。
 */
class St25PartialArtifactCrashIT extends PostgresITBase {

    @TempDir
    Path casDir;

    private StCheckpointHarness h;

    @BeforeEach
    void setUp() {
        h = new StCheckpointHarness(casDir);
    }

    @Test
    void crashBetweenTwoCasPutsLeavesNoCheckpointAndReruns() {
        StCheckpointHarness.Seed seed = h.seedFirstRun(103, "head-st25a", StCheckpointHarness.PROMPT_V1);
        StCheckpointCrashArtifactStore crashCas = new StCheckpointCrashArtifactStore(h.cas);
        MockModelGateway model = StCheckpointHarness.modelReturningOutput();
        crashCas.armFailOnPut(2); // findings blob 已落、model response blob 未写即崩

        WorkItemWorker workerA = h.newWorker("st25a-worker-a",
                h.newReviewExecutor(crashCas, h.newCheckpointWriter(), model));
        workerA.runOnce();

        assertThat(model.requests()).hasSize(1);
        assertThat(count("step_checkpoint")).as("双 CAS 中间崩溃：无 checkpoint 行").isZero();
        assertThat(count("artifact")).as("登记在 checkpoint 事务内，未发生").isZero();
        assertThat(h.eventCount(seed.runId(), "CHECKPOINT_STORED")).isZero();

        // 进程重启后续跑：无复用锚点 → 整体重调模型；孤儿 findings blob 被同 digest 幂等覆盖
        h.forceClaimable(seed.workItemId());
        model.enqueueContent(StCheckpointHarness.MODEL_OUTPUT);
        WorkItemWorker workerB = h.newWorker("st25a-worker-b",
                h.newReviewExecutor(crashCas, h.newCheckpointWriter(), model));
        workerB.runOnce();

        assertThat(model.requests()).as("重调模型，计数 2").hasSize(2);
        assertThat(h.eventCount(seed.runId(), "CHECKPOINT_REUSED"))
                .as("半成品不得部分复用").isZero();
        assertThat(h.eventCount(seed.runId(), "CHECKPOINT_DISCARDED"))
                .as("无 checkpoint 行可丢弃，不应有 DISCARDED").isZero();
        assertThat(count("step_checkpoint")).isEqualTo(1);
        assertThat(count("artifact")).isEqualTo(4); // 双 artifact + T2 双 payload
        assertThat(h.stepState(seed.stepId())).isEqualTo("SUCCEEDED");
    }

    @Test
    void checkpointPresentButModelBlobMissingDiscardsAndReruns() throws Exception {
        StCheckpointHarness.Seed seed = h.seedFirstRun(104, "head-st25b", StCheckpointHarness.PROMPT_V1);
        MockModelGateway model = StCheckpointHarness.modelReturningOutput();

        // attempt#1 完整写入（模型 + 双 CAS + checkpoint 行），随后崩溃（不做 T2）
        StCheckpointHarness.Claimed first = h.claim("st25b-worker-a");
        StepOutcome outcome = h.newReviewExecutor(model).execute(first.context(), () -> true);
        assertThat(outcome).isInstanceOf(StepOutcome.Succeeded.class);
        assertThat(count("step_checkpoint")).isEqualTo(1);

        // 残局注入：model response blob 没活下来（等价"只写一个 CAS artifact"的另一面）
        StepCheckpoint checkpoint = h.checkpointRepo.find(seed.stepId(), StepCheckpoint.REVIEW_OUTCOME)
                .orElseThrow();
        Files.delete(casDir.resolve(checkpoint.modelResponseDigest().value().substring(0, 2))
                .resolve(checkpoint.modelResponseDigest().value()));

        // 租约到期 → 新进程接管 → resume 判不完整 → 整体丢弃重跑
        h.forceLeaseExpired(seed.workItemId());
        model.enqueueContent(StCheckpointHarness.MODEL_OUTPUT);
        WorkItemWorker workerB = h.newWorker("st25b-worker-b", model);
        workerB.runOnce();

        assertThat(model.requests()).as("不完整 checkpoint 不得复用，重调模型").hasSize(2);
        assertThat(h.eventCount(seed.runId(), "CHECKPOINT_DISCARDED")).isEqualTo(1);
        assertThat(h.lastDiscardReason(seed.runId())).isEqualTo("CAS_MISSING_MODEL_RESPONSE");
        assertThat(h.eventCount(seed.runId(), "CHECKPOINT_REUSED")).isZero();
        assertThat(h.stepState(seed.stepId())).isEqualTo("SUCCEEDED");
        assertThat(h.runState(seed.runId())).isEqualTo("REVIEW_COMPLETE");
    }
}
