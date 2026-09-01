package com.objwww.pr.control.it;

import com.objwww.pr.control.application.WorkItemWorker;
import com.objwww.pr.control.domain.ai.MockModelClient;
import com.objwww.pr.control.domain.model.StepCheckpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ST-26（方案 §11 L3 表，回指 §4.2/R-R2）——双 artifact 写完、checkpoint 事务前崩溃：
 * 孤儿 artifact 无害，续跑重调模型，artifact 表无重复行。
 *
 * <p>场景（注入）：{@link StCheckpointCrashCheckpointWriter} 在 store 首次调用抛
 * SimulatedCrash——两个 CAS blob 均已落盘（putIfAbsent 先于事务），但登记 +
 * checkpoint upsert 的短事务从未开始。Worker 归类 retryable → RETRY_WAIT；
 * 拨快退避后新进程重跑。
 *
 * <p>预期断言：
 * <ul>
 *   <li>崩溃后：step_checkpoint 0 行、artifact 0 行（登记在事务内，随崩溃整体不存在）；</li>
 *   <li>恢复后：模型计数 2；artifact 恰 4 行且 digest 全唯一（同 digest 重放靠
 *       putIfAbsent + ON CONFLICT DO NOTHING 幂等，不产生重复行）；</li>
 *   <li>checkpoint 由 attempt#2 写入；终态正常闭环。</li>
 * </ul>
 *
 * <p>取证：模型计数 / artifact（无重复行）/ step_checkpoint / execution_event。
 */
class St26CrashBeforeCheckpointTxIT extends PostgresITBase {

    @TempDir
    Path casDir;

    private StCheckpointHarness h;

    @BeforeEach
    void setUp() {
        h = new StCheckpointHarness(casDir);
    }

    @Test
    void crashBeforeCheckpointTxRerunsModelWithoutDuplicateArtifacts() throws Exception {
        StCheckpointHarness.Seed seed = h.seedFirstRun(105, "head-st26", StCheckpointHarness.PROMPT_V1);
        MockModelClient model = StCheckpointHarness.modelReturningOutput();
        var crashWriter = new StCheckpointCrashCheckpointWriter(h.artifactRepo, h.checkpointRepo,
                h.ledger);

        WorkItemWorker workerA = h.newWorker("st26-worker-a",
                h.newReviewExecutor(h.cas, StCheckpointHarness.transactionalProxy(crashWriter), model));
        workerA.runOnce();

        assertThat(model.requests()).hasSize(1);
        assertThat(count("step_checkpoint")).as("checkpoint 事务未发生").isZero();
        assertThat(count("artifact")).as("登记随事务整体不存在").isZero();
        // 孤儿：双 CAS blob 已落盘（digest 寻址无害，R-R2 接受，GC 归 M6）——
        // CAS 目录 = 2 输入 blob（快照+diff）+ 2 孤儿产出 blob
        long casFiles;
        try (var stream = java.nio.file.Files.walk(casDir)) {
            casFiles = stream.filter(java.nio.file.Files::isRegularFile).count();
        }
        assertThat(casFiles).as("双 CAS blob 孤儿留存").isEqualTo(4);

        h.forceClaimable(seed.workItemId());
        model.enqueueContent(StCheckpointHarness.MODEL_OUTPUT);
        WorkItemWorker workerB = h.newWorker("st26-worker-b",
                h.newReviewExecutor(model));
        workerB.runOnce();

        assertThat(model.requests()).as("无 checkpoint → 重调模型，计数 2").hasSize(2);
        assertThat(count("artifact")).isEqualTo(4);
        Long distinctDigests = adminJdbc.sql("SELECT count(DISTINCT digest) FROM artifact")
                .query(Long.class).single();
        assertThat(distinctDigests).as("artifact 表无重复行").isEqualTo(4);
        assertThat(count("step_checkpoint")).isEqualTo(1);
        assertThat(h.checkpointRepo.find(seed.stepId(), StepCheckpoint.REVIEW_OUTCOME)
                .orElseThrow().attemptNo()).isEqualTo(2);
        assertThat(h.stepState(seed.stepId())).isEqualTo("SUCCEEDED");
        assertThat(h.runState(seed.runId())).isEqualTo("REVIEW_COMPLETE");
    }
}
