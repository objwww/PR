package com.objwww.pr.control.it;

import com.objwww.pr.control.domain.model.StepCheckpoint;
import com.objwww.pr.control.infrastructure.persistence.PostgresStepCheckpointRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresWorkItemRepository;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.WorkItemState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CT-26（docs/M2-技术方案.md §11 L2 表，回指 I25 / §4.2 迟到写栅栏）。
 *
 * <p>场景：双 Worker 租约接管后，旧 attempt 晚到写 checkpoint——真实并发：
 * attempt A（worker-a，lease_epoch=1）租约已过期；attempt B（worker-b）经
 * reclaimExpiredLease + claimNext 真实接管并把 lease_epoch 推进到 3；A 的晚到
 * upsert 与 B 的写入并发交错。
 *
 * <p>断言：A 的每次晚到写都 0 行（upsertIfLeaseCurrent=false，INSERT/UPDATE 双分支
 * 均被栅栏拦截）；B 的 checkpoint 正常落库且不被 A 覆盖；work_item.lease_epoch 不回退。
 *
 * <p>取证：step_checkpoint.output_artifact_digest / lease_epoch / attempt_no；
 * work_item.lease_epoch / lease_owner / state。
 *
 * <p>与 CT23CheckpointFenceIT 的关系：CT23 已以顺序方式覆盖同语义（admin UPDATE 模拟
 * epoch 推进）；本类补强为真实接管路径（reclaim/claim 仓储方法）+ 真并发交错，不改其名。
 */
class CT26CheckpointFenceConcurrencyIT extends PostgresITBase {

    private UUID stepId;
    private UUID workItemId;
    private PostgresStepCheckpointRepository checkpoints;
    private PostgresWorkItemRepository workItems;

    @BeforeEach
    void seedExpiredLease() {
        RepairSeed seed = seedRepairScope("ct26");
        stepId = UUID.randomUUID();
        workItemId = UUID.randomUUID();
        adminJdbc.sql("""
                INSERT INTO run_step(id,review_run_id,step_key,operation_id,step_type,state,ordinal,
                    max_attempts,timeout_seconds,version,created_at,updated_at)
                VALUES (:id,:run,'review',:op,'REVIEW','RUNNING',1,3,600,0,now(),now())
                """).param("id", stepId).param("run", seed.runId()).param("op", UUID.randomUUID()).update();
        // attempt A 的租约已过期：lease_until 在过去，epoch=1，owner=worker-a
        adminJdbc.sql("""
                INSERT INTO work_item(id,review_run_id,step_id,work_type,state,priority,available_at,
                    lease_owner,lease_until,lease_epoch,attempt_count,max_attempts,created_at,updated_at)
                VALUES (:id,:run,:step,'REVIEW','LEASED',0,now(),'worker-a',now()-interval '1 second',
                    1,1,3,now(),now())
                """).param("id", workItemId).param("run", seed.runId()).param("step", stepId).update();
        checkpoints = new PostgresStepCheckpointRepository(controlJdbc);
        workItems = new PostgresWorkItemRepository(controlJdbc);
    }

    @Test
    void realTakeoverThenLateWriteIsFenced() {
        // attempt B 真实接管：过期回收（epoch 1→2）+ 重新领取（epoch 2→3，owner=worker-b）
        assertThat(workItems.reclaimExpiredLease(workItemId, 1, WorkItemState.READY)).isTrue();
        var claimed = workItems.claimNext("worker-b", 600);
        assertThat(claimed).isPresent();
        assertThat(claimed.get().getLeaseEpoch()).isEqualTo(3);

        // A 晚到写（INSERT 分支栅栏）：0 行，checkpoint 表保持空
        assertThat(checkpoints.upsertIfLeaseCurrent(
                checkpoint("a-late", 1), workItemId, "worker-a")).isFalse();
        assertThat(count("step_checkpoint")).isZero();

        // B 正常写入
        assertThat(checkpoints.upsertIfLeaseCurrent(
                checkpoint("b-current", 3), workItemId, "worker-b")).isTrue();

        // A 再次晚到写（UPDATE 分支栅栏）：0 行，B 的 checkpoint 不被覆盖
        assertThat(checkpoints.upsertIfLeaseCurrent(
                checkpoint("a-late-2", 1), workItemId, "worker-a")).isFalse();
        StepCheckpoint stored = checkpoints.find(stepId, StepCheckpoint.REVIEW_OUTCOME).orElseThrow();
        assertThat(stored.outputArtifactDigest()).isEqualTo(Digest.sha256Of("b-current"));
        assertThat(stored.leaseEpoch()).isEqualTo(3);
        assertThat(stored.attemptNo()).isEqualTo(3);

        // 租约世代不回退
        assertThat(workItems.findById(workItemId).orElseThrow().getLeaseEpoch()).isEqualTo(3);
    }

    @Test
    void concurrentLateWriterNeverLandsDuringTakeover() throws Exception {
        // 租约已过期：A 的 epoch=1 写在任意交错下都必须 0 行
        // （过期瞬间 state=LEASED 但 now()>lease_until；回收后 state=READY；接管后 epoch/owner 均不符）
        CyclicBarrier barrier = new CyclicBarrier(2);
        ConcurrentLinkedQueue<Throwable> escaped = new ConcurrentLinkedQueue<>();
        CopyOnWriteArrayList<Boolean> lateWriteResults = new CopyOnWriteArrayList<>();

        Thread lateWriter = Thread.ofVirtual().name("ct26-worker-a").start(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < 50; i++) {
                    lateWriteResults.add(checkpoints.upsertIfLeaseCurrent(
                            checkpoint("a-late-" + i, 1), workItemId, "worker-a"));
                }
            } catch (Throwable t) {
                escaped.add(t);
            }
        });
        Thread takeover = Thread.ofVirtual().name("ct26-worker-b").start(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                if (workItems.reclaimExpiredLease(workItemId, 1, WorkItemState.READY)) {
                    var c = workItems.claimNext("worker-b", 600);
                    assertThat(c).isPresent();
                    assertThat(checkpoints.upsertIfLeaseCurrent(
                            checkpoint("b-current", c.get().getLeaseEpoch()),
                            workItemId, "worker-b")).isTrue();
                }
            } catch (Throwable t) {
                escaped.add(t);
            }
        });
        lateWriter.join(30_000);
        takeover.join(30_000);

        assertThat(escaped).isEmpty();
        // A 的 50 次晚到写全部被栅栏拦下
        assertThat(lateWriteResults).hasSize(50).allMatch(r -> !r);
        // B 的 checkpoint 落库且未被覆盖
        StepCheckpoint stored = checkpoints.find(stepId, StepCheckpoint.REVIEW_OUTCOME).orElseThrow();
        assertThat(stored.outputArtifactDigest()).isEqualTo(Digest.sha256Of("b-current"));
        assertThat(count("step_checkpoint")).isEqualTo(1);
    }

    private StepCheckpoint checkpoint(String content, long epoch) {
        return new StepCheckpoint(UUID.randomUUID(), stepId, StepCheckpoint.REVIEW_OUTCOME,
                Digest.sha256Of(content), Digest.sha256Of("model-" + content),
                Digest.sha256Of("contract"), "prompt", "schema", "mapper", "context", "model",
                epoch, (int) epoch, Instant.now());
    }
}
