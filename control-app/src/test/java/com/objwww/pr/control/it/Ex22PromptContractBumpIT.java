package com.objwww.pr.control.it;

import com.objwww.pr.control.application.StepOutcome;
import com.objwww.pr.control.application.WorkItemWorker;
import com.objwww.pr.control.domain.ai.MockModelGateway;
import com.objwww.pr.control.domain.model.StepCheckpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-22（方案 §11 L4 表，回指 I18/E9）——prompt 常量 bump 后旧 checkpoint 不得复用：
 * 丢弃重跑 + DISCARDED(CONTRACT_CHANGED:prompt)。
 *
 * <p>场景（注入）：attempt#1 以 prompt_version=m2-prompt-v1 完整写 checkpoint 后崩溃
 * （不 T2）；随后 bump——诚实注入说明：契约首分量为
 * {@code run.promptVersion + "/" + ReviewAgentLoop.PROMPT_TEMPLATE_VERSION}，其中
 * PROMPT_TEMPLATE_VERSION 是编译期常量（javac 内联），测试侧反射改写不生效，故以
 * admin SQL bump review_run.prompt_version 等价模拟"prompt 变更后旧 run 续跑看到契约
 * 漂移"（同一判定代码路径、同一精确 reason，未改主代码）。
 *
 * <p>预期断言：CHECKPOINT_DISCARDED 恰 1 条且 payload.reason = CONTRACT_CHANGED:prompt；
 * 零 CHECKPOINT_REUSED；模型计数 2；重跑后 checkpoint 行五分量为新契约、终态闭环。
 *
 * <p>取证：execution_event(payload.reason) / 模型计数 / step_checkpoint(prompt_template_version 列)。
 */
class Ex22PromptContractBumpIT extends PostgresITBase {

    @TempDir
    Path casDir;

    private StCheckpointHarness h;

    @BeforeEach
    void setUp() {
        h = new StCheckpointHarness(casDir);
    }

    @Test
    void promptBumpDiscardsCheckpointAndReruns() {
        StCheckpointHarness.Seed seed = h.seedFirstRun(112, "head-ex22", StCheckpointHarness.PROMPT_V1);
        MockModelGateway model = StCheckpointHarness.modelReturningOutput();

        // attempt#1 完整写入后崩溃（T2 前窗口）
        StCheckpointHarness.Claimed first = h.claim("ex22-worker-a");
        StepOutcome outcome = h.newReviewExecutor(model).execute(first.context(), () -> true);
        assertThat(outcome).isInstanceOf(StepOutcome.Succeeded.class);
        StepCheckpoint before = h.checkpointRepo.find(seed.stepId(), StepCheckpoint.REVIEW_OUTCOME)
                .orElseThrow();
        assertThat(before.promptTemplateVersion()).startsWith(StCheckpointHarness.PROMPT_V1 + "/");

        // prompt bump（注入说明见类 javadoc）
        h.bumpPromptVersion(seed.runId(), StCheckpointHarness.PROMPT_V2);

        // 接管重跑：契约漂移 → 丢弃 → 重调模型 → 新契约覆盖 checkpoint
        h.forceLeaseExpired(seed.workItemId());
        model.enqueueContent(StCheckpointHarness.MODEL_OUTPUT);
        WorkItemWorker workerB = h.newWorker("ex22-worker-b", model);
        workerB.runOnce();

        assertThat(model.requests()).as("契约漂移 → 重调模型").hasSize(2);
        assertThat(h.eventCount(seed.runId(), "CHECKPOINT_DISCARDED")).isEqualTo(1);
        assertThat(h.lastDiscardReason(seed.runId())).isEqualTo("CONTRACT_CHANGED:prompt");
        assertThat(h.eventCount(seed.runId(), "CHECKPOINT_REUSED")).isZero();
        StepCheckpoint after = h.checkpointRepo.find(seed.stepId(), StepCheckpoint.REVIEW_OUTCOME)
                .orElseThrow();
        assertThat(after.promptTemplateVersion())
                .startsWith(StCheckpointHarness.PROMPT_V2 + "/");
        assertThat(after.attemptNo()).isEqualTo(2);
        assertThat(h.stepState(seed.stepId())).isEqualTo("SUCCEEDED");
        assertThat(h.runState(seed.runId())).isEqualTo("REVIEW_COMPLETE");
    }
}
