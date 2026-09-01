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
 * ST-23（方案 §11 L3 表，回指 §4.2/I19）——首次执行（无 checkpoint）的冷路径基线。
 *
 * <p>场景：无任何历史 checkpoint，Worker 整链路 runOnce 单轮驱动
 * （领取 → 执行 → checkpoint 短事务 → T2 收尾）。
 *
 * <p>预期断言（可机器检查）：
 * <ul>
 *   <li>模型调用恰 1 次（MockModelClient.requests 留痕）；</li>
 *   <li>step_checkpoint 恰 1 行（key=REVIEW_OUTCOME，lease_epoch/attempt_no=1）；</li>
 *   <li>artifact 登记 4 行：FINDING_BODY + MODEL_RESPONSE + 2×REVIEW_PAYLOAD（T2 outbox
 *       payload），digest 全唯一；</li>
 *   <li>CHECKPOINT_STORED 事件恰 1 条，payload 双 digest 与 artifact 行对得上；</li>
 *   <li>闭环：Step SUCCEEDED / Run REVIEW_COMPLETE / work_item DONE / outbox 2 条 PENDING。</li>
 * </ul>
 *
 * <p>取证：step_checkpoint / artifact / execution_event / run_step / review_run / outbox_command。
 */
class St23FirstRunCheckpointIT extends PostgresITBase {

    @TempDir
    Path casDir;

    private StCheckpointHarness h;

    @BeforeEach
    void setUp() {
        h = new StCheckpointHarness(casDir);
    }

    @Test
    void firstRunStoresCheckpointAndBothArtifacts() {
        StCheckpointHarness.Seed seed = h.seedFirstRun(101, "head-st23", StCheckpointHarness.PROMPT_V1);
        MockModelClient model = StCheckpointHarness.modelReturningOutput();
        WorkItemWorker worker = h.newWorker("st23-worker", model);

        worker.runOnce();

        // 模型恰 1 次
        assertThat(model.requests()).as("首次执行模型调用恰 1 次").hasSize(1);

        // checkpoint 行齐全（写入者世代/attempt 审计列）
        assertThat(count("step_checkpoint")).isEqualTo(1);
        StepCheckpoint checkpoint = h.checkpointRepo.find(seed.stepId(), StepCheckpoint.REVIEW_OUTCOME)
                .orElseThrow();
        assertThat(checkpoint.leaseEpoch()).isEqualTo(1);
        assertThat(checkpoint.attemptNo()).isEqualTo(1);

        // 双 artifact + T2 双 payload，digest 无重复
        assertThat(count("artifact")).isEqualTo(4);
        Long distinctDigests = adminJdbc.sql("SELECT count(DISTINCT digest) FROM artifact")
                .query(Long.class).single();
        assertThat(distinctDigests).as("artifact digest 无重复行").isEqualTo(4);
        Long findingBodies = adminJdbc.sql(
                        "SELECT count(*) FROM artifact WHERE artifact_type = 'FINDING_BODY'")
                .query(Long.class).single();
        Long modelResponses = adminJdbc.sql(
                        "SELECT count(*) FROM artifact WHERE artifact_type = 'MODEL_RESPONSE'")
                .query(Long.class).single();
        assertThat(findingBodies).isEqualTo(1);
        assertThat(modelResponses).isEqualTo(1);

        // CHECKPOINT_STORED 事件，payload digest 与登记行一致
        assertThat(h.eventCount(seed.runId(), "CHECKPOINT_STORED")).isEqualTo(1);
        String eventOutputDigest = adminJdbc.sql("""
                SELECT payload->>'output_artifact_digest' FROM execution_event
                 WHERE review_run_id = :r AND event_type = 'CHECKPOINT_STORED'
                """).param("r", seed.runId()).query(String.class).single();
        assertThat(eventOutputDigest).isEqualTo(checkpoint.outputArtifactDigest().value());

        // 正常闭环
        assertThat(h.stepState(seed.stepId())).isEqualTo("SUCCEEDED");
        assertThat(h.runState(seed.runId())).isEqualTo("REVIEW_COMPLETE");
        assertThat(h.workItemState(seed.workItemId())).isEqualTo("DONE");
        assertThat(count("review_finding")).isEqualTo(1);
        assertThat(count("outbox_command")).isEqualTo(2);
        assertThat(h.eventCount(seed.runId(), "CHECKPOINT_REUSED")).isZero();
        assertThat(h.eventCount(seed.runId(), "CHECKPOINT_DISCARDED")).isZero();
    }
}
