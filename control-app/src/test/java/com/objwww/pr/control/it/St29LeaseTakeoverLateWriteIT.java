package com.objwww.pr.control.it;

import com.objwww.pr.control.application.WorkItemWorker;
import com.objwww.pr.control.domain.ai.MockModelClient;
import com.objwww.pr.control.domain.model.StepCheckpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ST-29（方案 §11 L3 表，回指 §4.2/I25，与 CT-26 互补——此处走 Worker 整链路）：
 * 租约接管后旧 Worker 晚到写，checkpoint/Step/Run 均不回退，晚到写 0 行。
 *
 * <p>场景（注入）：worker-a 领取租约后模型调用挂起（{@link StCheckpointBlockingModelClient}
 * 双闩）= "执行中"窗口；主线程拨过期租约，worker-b（新进程）runOnce 完整跑通
 * （回收 → 重领 → 模型 → checkpoint → T2 → DONE）；随后放行 worker-a——
 * 它的晚到 checkpoint upsert 撞 lease_epoch 栅栏（0 行），晚到 T2 撞
 * transitionIfLeaseCurrent 栅栏（STALE_IGNORED）。
 *
 * <p>预期断言：
 * <ul>
 *   <li>step_checkpoint 仍是 worker-b 的代际（lease_epoch=3、attempt_no=2），未被覆盖；</li>
 *   <li>work_item DONE 且 lease_epoch=3；Step SUCCEEDED、Run REVIEW_COMPLETE 不回退；</li>
 *   <li>worker-a 的 attempt#1 落 STALE；artifact/finding/outbox 行数在晚到写前后不变
 *       （两 Worker 模型产出同内容 → CAS/登记全幂等，晚到写净增 0 行）。</li>
 * </ul>
 *
 * <p>取证：step_checkpoint(lease_epoch/attempt_no) / work_item.lease_epoch /
 * step_attempt.status / 各表计数。
 */
class St29LeaseTakeoverLateWriteIT extends PostgresITBase {

    @TempDir
    Path casDir;

    private StCheckpointHarness h;

    @BeforeEach
    void setUp() {
        h = new StCheckpointHarness(casDir);
    }

    @Test
    void lateWriteFromOldWorkerIsFencedAndNothingRegresses() throws Exception {
        StCheckpointHarness.Seed seed = h.seedFirstRun(109, "head-st29", StCheckpointHarness.PROMPT_V1);
        StCheckpointBlockingModelClient blockingModel =
                new StCheckpointBlockingModelClient(StCheckpointHarness.MODEL_OUTPUT);
        MockModelClient modelB = StCheckpointHarness.modelReturningOutput();

        // worker-a：领租约 → 执行 → 卡在模型调用内（"执行中"窗口）
        WorkItemWorker workerA = h.newWorker("st29-worker-a", blockingModel);
        Thread threadA = Thread.ofVirtual().name("st29-worker-a").start(workerA::runOnce);
        blockingModel.awaitEntered(); // 此刻 worker-a 已持租约（epoch 1）

        // 租约到期 → worker-b 接管并完整闭环
        h.forceLeaseExpired(seed.workItemId());
        WorkItemWorker workerB = h.newWorker("st29-worker-b", modelB);
        workerB.runOnce();

        assertThat(h.workItemState(seed.workItemId())).isEqualTo("DONE");
        assertThat(h.workItemEpoch(seed.workItemId()))
                .as("接管后 epoch：a 领取 1 → 回收 2 → b 领取 3").isEqualTo(3);
        long artifactsAfterB = count("artifact");
        long checkpointsAfterB = count("step_checkpoint");
        long findingsAfterB = count("review_finding");
        long outboxAfterB = count("outbox_command");

        // 放行 worker-a：晚到写必须全部被栅栏拦下
        blockingModel.release();
        threadA.join(Duration.ofSeconds(30));
        assertThat(threadA.isAlive()).as("worker-a 未在 30s 内收尾").isFalse();

        // checkpoint/Step/Run 不回退
        StepCheckpoint checkpoint = h.checkpointRepo.find(seed.stepId(), StepCheckpoint.REVIEW_OUTCOME)
                .orElseThrow();
        assertThat(checkpoint.leaseEpoch()).as("晚到 checkpoint 写 0 行：仍是 b 的代际").isEqualTo(3);
        assertThat(checkpoint.attemptNo()).isEqualTo(2);
        assertThat(count("step_checkpoint")).isEqualTo(checkpointsAfterB);
        assertThat(h.stepState(seed.stepId())).isEqualTo("SUCCEEDED");
        assertThat(h.runState(seed.runId())).isEqualTo("REVIEW_COMPLETE");
        assertThat(h.workItemState(seed.workItemId())).isEqualTo("DONE");
        assertThat(h.workItemEpoch(seed.workItemId())).isEqualTo(3);

        // 晚到写净增 0 行（同内容 → CAS/登记幂等；栅栏拦 checkpoint）
        assertThat(count("artifact")).isEqualTo(artifactsAfterB);
        assertThat(count("review_finding")).isEqualTo(findingsAfterB);
        assertThat(count("outbox_command")).isEqualTo(outboxAfterB);

        // worker-a 的晚到结果被 T2 栅栏记 STALE（I11），attempt#2 正常 SUCCEEDED
        String attempt1 = adminJdbc.sql(
                        "SELECT status FROM step_attempt WHERE step_id = :s AND attempt_no = 1")
                .param("s", seed.stepId()).query(String.class).single();
        String attempt2 = adminJdbc.sql(
                        "SELECT status FROM step_attempt WHERE step_id = :s AND attempt_no = 2")
                .param("s", seed.stepId()).query(String.class).single();
        assertThat(attempt1).isEqualTo("STALE");
        assertThat(attempt2).isEqualTo("SUCCEEDED");
    }
}
