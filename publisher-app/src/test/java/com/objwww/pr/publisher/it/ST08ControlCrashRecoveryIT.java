package com.objwww.pr.publisher.it;

import com.objwww.pr.control.application.StepCompletion;
import com.objwww.pr.control.application.StepOutcome;
import com.objwww.pr.control.application.T2Outcome;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.shared.AttemptStatus;
import com.objwww.pr.shared.RunState;
import com.objwww.pr.shared.StepState;
import com.objwww.pr.shared.WorkItemState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ST-08 Control 崩溃恢复（F5）：worker 领取后、T2 提交前"被杀"（租约无人续）
 * → 租约过期 → 新进程（新 workerId）恢复扫描回收并重领执行 → Step 只成功一次；
 * 旧 worker 的晚到结果记 STALE 不推进（I11），不产生重复 findings/outbox 命令。
 */
class ST08ControlCrashRecoveryIT extends PostgresITBase {

    private static final String HEAD_SHA = "cc" + "3".repeat(38);

    private ItHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ItHarness(casDir, null);
    }

    @Test
    void crashedWorkerLeaseIsRecoveredAndLateResultMarkedStale() {
        ReviewRun run = harness.dispatchOpened(
                ItHarness.prEvent("st08-d1", 2009L, "objwww/mall", 29, HEAD_SHA, "opened"),
                ItTarballs.singleFile("src/A.java", "class A {}\n"), "diff");

        // worker-b 领取并记 attempt#1 后"进程被杀"（永不提交 T2）
        ItHarness.ClaimedWork crashed = harness.claimManually("worker-b");
        adminJdbc.sql("UPDATE work_item SET lease_until = :past WHERE id = :id")
                .param("past", Timestamp.from(Instant.now().minusSeconds(5)))
                .param("id", crashed.workItem().getId()).update();

        // 新进程 worker-c：恢复扫描（回收 READY）+ 重领（epoch2）+ 真执行 + T2 完成
        harness.modelClient.enqueueContent("[]");
        int processed = harness.newWorker("worker-c").runOnce();
        assertThat(processed).isEqualTo(2); // 回收 1 + 执行 1
        assertThat(harness.runRepo.findById(run.getId()).orElseThrow().getState())
                .isEqualTo(RunState.REVIEW_COMPLETE);
        assertThat(harness.stepRepo.findById(crashed.step().getId()).orElseThrow().getState())
                .isEqualTo(StepState.SUCCEEDED);
        assertThat(harness.workItemRepo.findById(crashed.workItem().getId()).orElseThrow().getState())
                .isEqualTo(WorkItemState.DONE);
        long findings = count("review_finding");
        long commands = count("outbox_command");
        assertThat(commands).isEqualTo(2); // CREATE_CHECK + PUBLISH_REVIEW，无重复

        // 旧 worker-b 的晚到结果：栅栏挡下记 STALE，一切不推进
        T2Outcome late = harness.completeStep(new StepCompletion(
                crashed.workItem().getId(), crashed.step().getId(), crashed.attempt().getId(),
                "worker-b", 1, new StepOutcome.Failed("Late", "LATE", "崩溃前残留", true)));
        assertThat(late).isEqualTo(T2Outcome.STALE_IGNORED);
        assertThat(harness.attemptRepo.findById(crashed.attempt().getId()).orElseThrow().getStatus())
                .isEqualTo(AttemptStatus.STALE);
        assertThat(count("review_finding")).isEqualTo(findings);
        assertThat(count("outbox_command")).isEqualTo(commands);
        assertThat(harness.runRepo.findById(run.getId()).orElseThrow().getState())
                .isEqualTo(RunState.REVIEW_COMPLETE);
    }
}
