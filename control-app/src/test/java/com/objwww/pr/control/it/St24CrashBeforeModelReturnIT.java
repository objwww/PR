package com.objwww.pr.control.it;

import com.objwww.pr.control.application.WorkItemWorker;
import com.objwww.pr.control.domain.model.StepCheckpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ST-24（方案 §11 L3 表，回指 §4.2）——模型返回前崩溃：checkpoint 之前无任何持久化，
 * 下一 attempt 允许（且必须）重新调用模型。
 *
 * <p>场景（注入）：{@link StCheckpointCrashOnceModelClient} 首次模型调用抛
 * SimulatedCrash（等价 mock 模型挂起时 kill executor——进程死亡，模型响应从未返回，
 * CAS/checkpoint 均未写）。Worker 归类 Unexpected/retryable → T2 记 RETRY_WAIT；
 * 拨快退避后以新 workerId（进程重启）再跑一轮。
 *
 * <p>预期断言：
 * <ul>
 *   <li>崩溃后：step_checkpoint 0 行、artifact 0 行、零 CHECKPOINT_* 事件；</li>
 *   <li>恢复后第二轮成功闭环：模型计数恰 2；checkpoint 行由 attempt#2 写入
 *       （attempt_no=2、lease_epoch=2）；attempt#1 FAILED_RETRYABLE 留痕。</li>
 * </ul>
 *
 * <p>取证：step_checkpoint（空 → 1 行）/ 模型计数 / step_attempt / execution_event。
 */
class St24CrashBeforeModelReturnIT extends PostgresITBase {

    @TempDir
    Path casDir;

    private StCheckpointHarness h;

    @BeforeEach
    void setUp() {
        h = new StCheckpointHarness(casDir);
    }

    @Test
    void crashBeforeModelReturnLeavesNoCheckpointAndRetries() {
        StCheckpointHarness.Seed seed = h.seedFirstRun(102, "head-st24", StCheckpointHarness.PROMPT_V1);
        StCheckpointCrashOnceModelClient model =
                new StCheckpointCrashOnceModelClient(StCheckpointHarness.MODEL_OUTPUT);

        // attempt#1：模型返回前崩溃
        WorkItemWorker workerA = h.newWorker("st24-worker-a", model);
        workerA.runOnce();

        assertThat(model.calls()).isEqualTo(1);
        assertThat(count("step_checkpoint")).as("模型返回前崩溃：无 checkpoint").isZero();
        assertThat(count("artifact")).as("模型返回前崩溃：无 artifact 登记").isZero();
        assertThat(h.eventCount(seed.runId(), "CHECKPOINT_STORED")).isZero();
        assertThat(h.workItemState(seed.workItemId())).isEqualTo("RETRY_WAIT");
        String attempt1Status = adminJdbc.sql(
                        "SELECT status FROM step_attempt WHERE step_id = :s AND attempt_no = 1")
                .param("s", seed.stepId()).query(String.class).single();
        assertThat(attempt1Status).isEqualTo("FAILED_RETRYABLE");

        // 进程重启（新 workerId），退避到期后重领 → 允许再调模型
        h.forceClaimable(seed.workItemId());
        WorkItemWorker workerB = h.newWorker("st24-worker-b", model);
        workerB.runOnce();

        assertThat(model.calls()).as("下一 attempt 允许再调模型，总计数 2").isEqualTo(2);
        assertThat(count("step_checkpoint")).isEqualTo(1);
        var checkpoint = h.checkpointRepo.find(seed.stepId(), StepCheckpoint.REVIEW_OUTCOME)
                .orElseThrow();
        assertThat(checkpoint.attemptNo()).isEqualTo(2);
        assertThat(checkpoint.leaseEpoch()).isEqualTo(2);
        assertThat(h.stepState(seed.stepId())).isEqualTo("SUCCEEDED");
        assertThat(h.runState(seed.runId())).isEqualTo("REVIEW_COMPLETE");
    }
}
