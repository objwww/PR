package com.objwww.pr.control.it;

import com.objwww.pr.control.application.StepOutcome;
import com.objwww.pr.control.domain.ai.MockModelClient;
import com.objwww.pr.control.domain.model.StepCheckpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ST-27（方案 §11 L3 表，G1 重点关注，回指 §4.2/I19）——checkpoint 提交后、T2 前崩溃：
 * 续跑零模型调用，output digest 与崩溃前逐位相同，命中路径零新增 artifact 行。
 *
 * <p>场景（注入）：attempt#1 手工三段驱动（领取 → 执行 → <b>不收尾即"崩溃"</b>：
 * checkpoint 短事务已提交，T2 从未发起，attempt 行留 STARTED 僵尸）；拨过期租约，
 * 新 workerId 接管后 attempt#2 同样手工驱动，精确夹住"仅执行器段"做 I19 断言
 * （T2 的 REVIEW_PAYLOAD 登记属发布路径，不在本断言窗口内）。
 *
 * <p>预期断言：
 * <ul>
 *   <li>续跑模型计数仍为 1（零模型调用）；</li>
 *   <li>attempt#2 的 output digest == 崩溃前 checkpoint 行的 output_artifact_digest；</li>
 *   <li>CHECKPOINT_REUSED 事件恰 1 条（payload 指回崩溃前 checkpoint_id）；</li>
 *   <li><b>I19 补断言：命中路径执行器段前后 artifact 行数不变（零新增）</b>；</li>
 *   <li>attempt#2 收尾后正常闭环（Step SUCCEEDED / Run REVIEW_COMPLETE）。</li>
 * </ul>
 *
 * <p>取证：模型计数 / step_checkpoint / artifact 行数 / execution_event。
 */
class St27CheckpointResumeZeroModelIT extends PostgresITBase {

    @TempDir
    Path casDir;

    private StCheckpointHarness h;

    @BeforeEach
    void setUp() {
        h = new StCheckpointHarness(casDir);
    }

    @Test
    void resumeAfterCheckpointCommitCallsModelZeroTimes() {
        StCheckpointHarness.Seed seed = h.seedFirstRun(106, "head-st27", StCheckpointHarness.PROMPT_V1);
        MockModelClient model = StCheckpointHarness.modelReturningOutput();

        // attempt#1：checkpoint 提交后"进程死亡"（T2 前窗口）
        StCheckpointHarness.Claimed first = h.claim("st27-worker-a");
        StepOutcome crashed = h.newReviewExecutor(model).execute(first.context(), () -> true);
        assertThat(crashed).isInstanceOf(StepOutcome.Succeeded.class);
        String preCrashDigest = ((StepOutcome.Succeeded) crashed).outputArtifactDigest().value();
        assertThat(count("step_checkpoint")).isEqualTo(1);
        assertThat(count("artifact")).isEqualTo(2); // 双 artifact 已登记，T2 未跑
        // —— 崩溃：不对 first 做任何收尾 ——

        // 租约到期 → 接管（reclaim 回 READY）→ attempt#2 领取
        h.forceLeaseExpired(seed.workItemId());
        var workerB = h.newWorker("st27-worker-b", model);
        assertThat(workerB.recoverExpiredLeases()).isEqualTo(1);
        StCheckpointHarness.Claimed second = h.claim("st27-worker-b");
        assertThat(second.item().getAttemptCount()).isEqualTo(2);

        long artifactsBeforeResume = count("artifact");
        StepOutcome resumed = h.newReviewExecutor(model).execute(second.context(), () -> true);

        // 零模型调用 + digest 逐位相同 + REUSED 事件
        assertThat(model.requests()).as("checkpoint 命中：续跑零模型调用").hasSize(1);
        String resumedDigest = ((StepOutcome.Succeeded) resumed).outputArtifactDigest().value();
        assertThat(resumedDigest).as("output digest 与崩溃前相同").isEqualTo(preCrashDigest);
        assertThat(h.eventCount(seed.runId(), "CHECKPOINT_REUSED")).isEqualTo(1);
        StepCheckpoint checkpoint = h.checkpointRepo.find(seed.stepId(), StepCheckpoint.REVIEW_OUTCOME)
                .orElseThrow();
        assertThat(checkpoint.outputArtifactDigest().value()).isEqualTo(resumedDigest);
        String reusedCheckpointId = adminJdbc.sql("""
                SELECT payload->>'checkpoint_id' FROM execution_event
                 WHERE review_run_id = :r AND event_type = 'CHECKPOINT_REUSED'
                """).param("r", seed.runId()).query(String.class).single();
        assertThat(reusedCheckpointId).isEqualTo(checkpoint.id().toString());

        // I19：命中路径零新增 artifact 行（执行器段窗口）
        assertThat(count("artifact")).as("命中路径零新增 artifact 行")
                .isEqualTo(artifactsBeforeResume);

        // 收尾闭环（attempt#2 的 T2）
        h.complete(second, resumed);
        assertThat(h.stepState(seed.stepId())).isEqualTo("SUCCEEDED");
        assertThat(h.runState(seed.runId())).isEqualTo("REVIEW_COMPLETE");
        assertThat(count("review_finding")).isEqualTo(1);
        assertThat(count("outbox_command")).isEqualTo(2);
        assertThat(model.requests()).as("全程模型调用恰 1 次").hasSize(1);
    }
}
