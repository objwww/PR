package com.objwww.pr.control.alert.application.mutation;

import com.objwww.pr.control.alert.domain.mutation.OperationStatus;
import com.objwww.pr.control.alert.domain.mutation.RcaOperation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 操作台账端口（PB-B4，V114 rca_operation）：状态推进必须过域状态机
 * （{@link OperationStateMachine}），DB 面以当前态 CAS 兜底并发——
 * 返回 false = 并发赢家是别人（调用方幂等让步）。
 */
public interface OperationLedgerStore {

    void insert(RcaOperation operation);

    /** 领取锁时回填代数（§2.9 acquire 在 operation 行之后同事务执行） */
    void updateResourceEpoch(UUID operationId, long resourceEpoch);

    Optional<RcaOperation> findById(UUID operationId);

    /**
     * 状态推进 CAS：仅当 DB 当前态 = expected 时应用 next（域状态机先验，
     * DB CAS 兜底并发）。时间戳由本端口按目标态落（dispatched/ack/verified/completed）。
     */
    boolean transition(UUID operationId, OperationStatus expected, OperationStatus next,
            Instant at);

    /** 指定状态全集（B5 reconcile 扫描面：UNKNOWN / PREPARED 悬挂） */
    java.util.List<UUID> idsInStatus(OperationStatus status);

    /** run 级活跃 mutation 查询（闸口面：BUSY 态全集，含终态 ESCALATED 之外的中间态） */
    boolean hasActiveForRun(UUID runId);
}
