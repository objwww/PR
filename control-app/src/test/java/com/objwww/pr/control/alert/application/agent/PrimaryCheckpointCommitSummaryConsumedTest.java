package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.RunConfigEpochRepository;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CL-07 消费指针围栏单测（SUMMARY_CONSUMED）：零推进字段仅 current_summary_id，
 * revision 条件写 +1；同 actionKey 重放 REPLAYED 收敛（不双推进）；陈旧 revision
 * 拒绝（STALE_REVISION 异常穿透回滚）。真实 PG 四写点屏障面在
 * PrimaryCheckpointCommitFenceIT（MC08 同律，195/真窗补证）。
 */
class PrimaryCheckpointCommitSummaryConsumedTest {

    private static final Instant NOW = Instant.parse("2026-09-13T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final UUID runId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final AlertInMemoryStores stores = new AlertInMemoryStores();
    private PrimaryCheckpointCommitService fence;

    @BeforeEach
    void setUp() {
        stores.runs.insert(new RcaRun(runId, UUID.randomUUID(), 0, RunTrigger.RERUN,
                RcaRunState.QUEUED, Digest.sha256Of("material"), NOW, NOW, null,
                null, null));
        stores.tasks.insert(new RcaTask(taskId, runId, RcaTask.PRIMARY_INVESTIGATE,
                RcaTaskState.READY, 5, NOW, NOW, Instant.MAX, null, null, 0, 0, 2,
                NOW, NOW, 0));
        stores.checkpoints.upsert(PrimaryCheckpoint.initial(taskId, runId, 0, NOW)
                .withStepAdvanced("src-digest-1", null, null, null, NOW));
        fence = new PrimaryCheckpointCommitService(stores.runs, stores.tasks,
                stores.checkpoints, RunConfigEpochRepository.NO_OP,
                new AlertClockStub(), inPlaceTx());
    }

    /** 生产装配同构的消费口 lambda（AlertAm4Config 复制面——APPLIED/REPLAYED 均收敛） */
    private boolean consume(long expectedRevision, UUID summaryId, String actionKey) {
        var st = fence.commit(
                new PrimaryCheckpointCommitService.CommitFence(runId, taskId, null,
                        0, null, expectedRevision),
                actionKey,
                PrimaryCheckpointCommitService.CommitMutation.SUMMARY_CONSUMED,
                cp -> cp.withSummaryConsumed(summaryId, NOW));
        return st.status() == PrimaryCheckpointCommitService.CommitStatus.APPLIED
                || st.status() == PrimaryCheckpointCommitService.CommitStatus.REPLAYED;
    }

    @Test
    @DisplayName("APPLIED：指针钉面 + revision+1；REPLAYED：同 actionKey 重放不双推进")
    void appliedThenReplayedConverges() {
        UUID summaryId = UUID.randomUUID();
        long revisionBefore = stores.checkpoints.findByTask(taskId).orElseThrow().revision();

        assertThat(consume(revisionBefore, summaryId, "summary-consumed:" + summaryId))
                .isTrue();
        PrimaryCheckpoint after = stores.checkpoints.findByTask(taskId).orElseThrow();
        assertThat(after.currentSummaryId()).isEqualTo(summaryId);
        assertThat(after.revision()).isEqualTo(revisionBefore + 1);
        assertThat(after.decisionSeq()).as("零推进字段：决策序不动")
                .isEqualTo(checkpoint().decisionSeq());
        assertThat(after.stepsUsed()).isEqualTo(checkpoint().stepsUsed());

        // 重驱语义：崩溃后重读现行 revision 再提交同动作 → REPLAYED 收敛不双推进
        long freshRevision = stores.checkpoints.findByTask(taskId).orElseThrow().revision();
        assertThat(consume(freshRevision, summaryId, "summary-consumed:" + summaryId))
                .as("同动作重放 REPLAYED 收敛=已消费").isTrue();
        assertThat(stores.checkpoints.findByTask(taskId).orElseThrow().revision())
                .as("重放不双推进").isEqualTo(revisionBefore + 1);
    }

    @Test
    @DisplayName("STALE_REVISION：陈旧修订拒绝异常穿透（保留旧指针）")
    void staleRevisionRejected() {
        UUID summaryId = UUID.randomUUID();
        long revision = stores.checkpoints.findByTask(taskId).orElseThrow().revision();

        assertThatThrownBy(() ->
                consume(revision - 1, summaryId, "summary-consumed:" + summaryId))
                .isInstanceOf(PrimaryCheckpointCommitService.CommitRejectedException.class)
                .hasMessageContaining("STALE_REVISION");
        assertThat(stores.checkpoints.findByTask(taskId).orElseThrow().currentSummaryId())
                .as("拒绝后指针不动").isNull();
    }

    private PrimaryCheckpoint checkpoint() {
        return stores.checkpoints.findByTask(taskId).orElseThrow();
    }

    private static TransactionOperations inPlaceTx() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    private static final class AlertClockStub
            implements com.objwww.pr.control.alert.application.AlertClock {
        @Override
        public Instant now() {
            return NOW;
        }
    }
}
