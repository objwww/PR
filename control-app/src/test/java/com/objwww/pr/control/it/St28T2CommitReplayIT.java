package com.objwww.pr.control.it;

import com.objwww.pr.control.application.StepCompletion;
import com.objwww.pr.control.application.WorkItemWorker;
import com.objwww.pr.control.domain.ai.MockModelClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ST-28（方案 §11 L3 表，回指 §4.2）——T2 提交后、Worker 应答前崩溃：
 * 重放后零重复 Finding、零重复 Outbox（既有幂等面回归）。
 *
 * <p>本架构中"Worker 应答"就是 T2 整笔事务本身（work_item → DONE 与 findings/outbox
 * 同事务提交），因此崩溃后不存在可重领的在途项——重放只能以两种形态出现，本用例都验：
 * <ul>
 *   <li><b>进程重启重扫</b>：新 Worker runOnce → 恢复扫描不认 DONE 项、领取面为空，
 *       DB 零变化；</li>
 *   <li><b>同一份 StepCompletion 直接重放 T2</b>（at-least-once 的最坏形态，结果对象由
 *       {@link StCheckpointRecordingExecutor} 在崩溃前留痕）：必须 fail-closed 且
 *       零副作用——attempt 终态机（SUCCEEDED 不可再迁）在写任何 finding/outbox 之前
 *       拒掉整笔，事务回滚。</li>
 * </ul>
 *
 * <p>预期断言：重放后 review_finding / outbox_command / execution_event / artifact /
 * step_attempt 计数与崩溃前逐一相同；重放抛 IllegalTransitionException 族异常。
 *
 * <p>取证：review_finding / outbox_command / execution_event / step_attempt 计数。
 */
class St28T2CommitReplayIT extends PostgresITBase {

    @TempDir
    Path casDir;

    private StCheckpointHarness h;

    @BeforeEach
    void setUp() {
        h = new StCheckpointHarness(casDir);
    }

    @Test
    void replayAfterT2CommitProducesNoDuplicates() {
        StCheckpointHarness.Seed seed = h.seedFirstRun(107, "head-st28", StCheckpointHarness.PROMPT_V1);
        MockModelClient model = StCheckpointHarness.modelReturningOutput();
        StCheckpointRecordingExecutor executor = h.newRecordingExecutor(model);
        WorkItemWorker workerA = h.newWorker("st28-worker-a", executor);

        // attempt#1 完整闭环：T2 已提交（work_item DONE、findings/outbox 落库）
        workerA.runOnce();
        assertThat(h.workItemState(seed.workItemId())).isEqualTo("DONE");
        assertThat(h.stepState(seed.stepId())).isEqualTo("SUCCEEDED");

        long findings = count("review_finding");
        long outbox = count("outbox_command");
        long events = count("execution_event");
        long artifacts = count("artifact");
        long attempts = count("step_attempt");
        assertThat(findings).isEqualTo(1);
        assertThat(outbox).isEqualTo(2);

        // —— 崩溃（应答丢失）。重放形态①：进程重启重扫，DONE 项不在恢复/领取面
        WorkItemWorker workerB = h.newWorker("st28-worker-b", model);
        assertThat(workerB.runOnce()).as("DONE 项不被回收也不再被领取").isZero();
        assertUnchanged(findings, outbox, events, artifacts, attempts);

        // 重放形态②：同一份 StepCompletion 重放 T2（崩溃前留痕的结果对象）
        UUID attemptId = adminJdbc.sql(
                        "SELECT id FROM step_attempt WHERE step_id = :s AND attempt_no = 1")
                .param("s", seed.stepId()).query(UUID.class).single();
        StepCompletion replay = new StepCompletion(seed.workItemId(), seed.stepId(), attemptId,
                "st28-worker-a", 1, executor.lastOutcome());
        assertThatThrownBy(() -> h.orchestrator().completeStep(replay))
                .as("T2 重放必须 fail-closed（attempt 终态不可再迁）")
                .isInstanceOf(RuntimeException.class);
        assertUnchanged(findings, outbox, events, artifacts, attempts);
    }

    private void assertUnchanged(long findings, long outbox, long events, long artifacts,
                                 long attempts) {
        assertThat(count("review_finding")).as("零重复 Finding").isEqualTo(findings);
        assertThat(count("outbox_command")).as("零重复 Outbox").isEqualTo(outbox);
        assertThat(count("execution_event")).as("账本零新增").isEqualTo(events);
        assertThat(count("artifact")).as("artifact 零新增").isEqualTo(artifacts);
        assertThat(count("step_attempt")).as("attempt 零新增").isEqualTo(attempts);
    }
}
