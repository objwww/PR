package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * rca_task 端口（调度列齐全；SLA 晋升排序 §6.2）。
 *
 * <p>SQL 契约：claimNext =
 * {@code UPDATE ... SET state='LEASED', ... WHERE id = (SELECT id FROM rca_task t
 * WHERE state IN ('READY','RETRY_WAIT') AND available_at <= :now
 * AND EXISTS (SELECT 1 FROM rca_run r WHERE r.id = t.run_id
 *             AND r.state IN ('QUEUED','RUNNING','REPORTING'))   -- M4-07 generation fence
 * ORDER BY (now() >= deadline_at) DESC, priority DESC,
 * deadline_at, created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING *}
 *
 * <p>requireCurrentLease = epoch 栅栏 UPDATE（行数 0 = 旧 worker/已回收）；
 * slot 领取与本 claim 同一短事务（INV-AM1-7，CT-A04）。
 */
public interface RcaTaskRepository {

    void insert(RcaTask task);

    /** SLA 排序领取（§6.2 ORDER BY；SKIP LOCKED 并发互斥 CT-A02） */
    Optional<RcaTask> claimNext(String owner, Instant now, Duration lease);

    /** epoch 栅栏：当前租约校验（1=仍持有；0=过期/易主） */
    boolean requireCurrentLease(UUID id, String owner, long leaseEpoch);

    /** 状态/调度列更新（调用方已过栅栏） */
    boolean update(RcaTask task);

    /** 心跳续租：lease_until = now + extend（仅当前租约） */
    void heartbeat(UUID id, String owner, long leaseEpoch, Instant now, Duration extend);

    /** 崩溃回收扫描：LEASED 且 lease_until < now */
    List<RcaTask> findExpiredLeased(Instant now);

    /**
     * EX-A2（F10）：过期租约原子回收——四条件同语句（id + state='LEASED' +
     * lease_until < :now + lease_epoch = :expectedEpoch）：读快照后原 worker 心跳已续
     * （lease_until 前移）/已他人重领（epoch+1）/他回收者已收敛（state≠LEASED）任一发生
     * 即 0 行 = 竞态失败，调用方零补救。target=RETRY_WAIT（活跃 run 重排）或
     * STALE（死 run 不复活，M4-07）；readyAt 为退避后的 available_at/ready_since，
     * 租约列清空，epoch/attempt_count 不动。默认实现不可用（条件写必须真实现）。
     */
    default boolean reclaimExpired(UUID id, long expectedEpoch, Instant now,
                                   RcaTaskState target, Instant readyAt) {
        throw new UnsupportedOperationException(
                "reclaimExpired 需原子条件写实现: " + getClass().getName());
    }

    Optional<RcaTask> findById(UUID id);

    /**
     * CL-01 行锁读（提交围栏锁序 run→task→checkpoint 的第二锁，须在调用方事务内）。
     * 默认回退非锁读（测试 fake 兼容）；生产实现必须 SELECT ... FOR UPDATE。
     */
    default Optional<RcaTask> findByIdForUpdate(UUID id) {
        return findById(id);
    }

    /** run 全量任务（推进器输入；返回序 = id 升序，稳定可复现） */
    List<RcaTask> findByRunId(UUID runId);

    /**
     * SR §4（SR08 真 PG 竞态实证）：按 id 序锁 run 的全部非终态 task 行。对账类
     * run 终止（过期）必须先取本锁再锁 run 行——与 finishTask 的 task→run 加锁序
     * 一致，消除 AB-BA 死锁（finishTask 持 task 锁等 run，过期方持 run 锁等 task）。
     * 默认回退非锁过滤读（测试 fake 单线程语义）；生产实现必须 SELECT ... FOR UPDATE。
     */
    default List<RcaTask> lockNonTerminalByRunIdForUpdate(UUID runId) {
        return findByRunId(runId).stream()
                .filter(t -> switch (t.state()) {
                    case DONE, CANCELLED, DEAD, SKIPPED, FAILED_TERMINAL, STALE -> false;
                    default -> true;
                })
                .toList();
    }

    /**
     * 状态 CAS 迁移（M4-06 推进器并发栅栏）：{@code UPDATE ... SET state=:to
     * WHERE id=:id AND state=:from}——1=本次迁移生效；0=并发已收敛/状态已漂移。
     * 状态机合法性由调用方保证（本端口只做行级竞态裁决）。
     */
    boolean transitionState(UUID id, RcaTaskState from, RcaTaskState to);

    /**
     * WC-4 §4.1：冲突即弃的插入（uq(run,round,task_key) 兜底并发铸造）。默认 =
     * insert + 捕获 DuplicateKeyException（fake 语义）；生产实现必须
     * {@code INSERT ... ON CONFLICT DO NOTHING}——PG 事务内唯一冲突会把事务置为
     * aborted，同事务后续语句全拒，不能靠异常后继续操作（方案 v2 §4.1）。
     */
    default boolean insertIfAbsent(RcaTask task) {
        try {
            insert(task);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException alreadyPresent) {
            return false;
        }
    }

    /**
     * WC-4 §5.3 终态 Run 清理通道：终态 Run 名下的非终态任务，按 (created_at,id)
     * keyset（独立游标/独立索引 V109，不与活跃对账批次争用）。驱动面 = 非终态任务集
     * （有界），join rca_run 判终态——不被全量历史终态 Run 拖慢。
     * 默认不可用（join 语义必须真实现；无 run 面的独立 fake 返回空）。
     */
    record OpenTaskRef(UUID taskId, UUID runId, Instant createdAt) {
    }

    default List<OpenTaskRef> findOpenTasksUnderTerminalRunsAfter(Instant afterCreatedAt,
            UUID afterId, int limit) {
        throw new UnsupportedOperationException(
                "findOpenTasksUnderTerminalRunsAfter 需 join 实现: " + getClass().getName());
    }

    /** WC-5：终态 Run 名下未决任务存量（gauge 数据面）。默认 0（无 join 语义环境） */
    default long countOpenTasksUnderTerminalRuns() {
        return 0;
    }

    /** 排队 task 数（DeferredPolicy backlog 输入） */
    int countQueued();
}
