package com.objwww.pr.publisher.it;

import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.publisher.domain.port.ExecutionEventAppender;
import com.objwww.pr.publisher.domain.port.PublicationStore;
import com.objwww.pr.publisher.domain.service.T3AContext;
import com.objwww.pr.publisher.domain.service.T3ADecision;
import com.objwww.pr.shared.ExecutionEvent;
import com.objwww.pr.shared.PublicationResourceType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * ST-03/ST-04 用：模拟 publisher 进程在 T3 中途被杀的 PublicationStore 包装器。
 *
 * <p>两种崩溃点（都是真实现的委托，只在关键时刻抛"进程死亡"异常）：
 * <ul>
 *   <li>{@link #crashAfterPrepareNext()} —— T3-A 已提交（命令 IN_FLIGHT 落库）、
 *       尚未触网（ST-04：远端未创建）；</li>
 *   <li>{@link #crashOnConfirmNext()} —— 远端已创建成功、T3-B confirm 未提交
 *       （ST-03：响应丢失窗口）。</li>
 * </ul>
 * 崩过的命令保持 IN_FLIGHT + 旧租约，由测试拨 lease_until 模拟时间推移后交 scanner 收敛。
 */
final class CrashyPublicationStore implements PublicationStore {

    /** 进程被杀：与真异常同构（RuntimeException），Executor/Claimer 的容错路径按意外处理 */
    static final class SimulatedCrash extends RuntimeException {
        SimulatedCrash(String point) {
            super("模拟 publisher 进程被杀: " + point);
        }
    }

    private final PublicationStore delegate;
    private boolean crashAfterPrepare;
    private boolean crashOnConfirm;

    CrashyPublicationStore(PublicationStore delegate) {
        this.delegate = delegate;
    }

    void crashAfterPrepareNext() {
        this.crashAfterPrepare = true;
    }

    void crashOnConfirmNext() {
        this.crashOnConfirm = true;
    }

    @Override
    public List<ClaimedCommand> claim(String leaseOwner, Duration leaseDuration, int batchSize) {
        return delegate.claim(leaseOwner, leaseDuration, batchSize);
    }

    @Override
    public T3ADecision prepare(UUID operationId, long leaseEpoch,
                               Function<T3AContext, T3ADecision> decider) {
        T3ADecision decision = delegate.prepare(operationId, leaseEpoch, decider);
        if (crashAfterPrepare && decision.action() == T3ADecision.Action.PROCEED) {
            crashAfterPrepare = false;
            throw new SimulatedCrash("T3-A 提交后、触网前");
        }
        return decision;
    }

    @Override
    public void confirm(UUID operationId, long leaseEpoch, String remoteId, String remoteUrl,
                        PublicationResourceType resourceType, String marker, ExecutionEvent event) {
        if (crashOnConfirm) {
            crashOnConfirm = false;
            throw new SimulatedCrash("远端已创建、T3-B confirm 提交前");
        }
        delegate.confirm(operationId, leaseEpoch, remoteId, remoteUrl, resourceType, marker, event);
    }

    // ---- 以下纯委托

    @Override
    public void markReconciling(UUID operationId, long leaseEpoch, Instant reconcileAfter,
                                ExecutionEvent event) {
        delegate.markReconciling(operationId, leaseEpoch, reconcileAfter, event);
    }

    @Override
    public void markRetryWait(UUID operationId, long leaseEpoch, Instant nextAttemptAt,
                              String errorCode) {
        delegate.markRetryWait(operationId, leaseEpoch, nextAttemptAt, errorCode);
    }

    @Override
    public void markSuperseded(UUID operationId, long leaseEpoch, String errorCode) {
        delegate.markSuperseded(operationId, leaseEpoch, errorCode);
    }

    @Override
    public void markFailedTerminal(UUID operationId, long leaseEpoch, String errorCode,
                                   ExecutionEvent event) {
        delegate.markFailedTerminal(operationId, leaseEpoch, errorCode, event);
    }

    @Override
    public void markManual(UUID operationId, long leaseEpoch, String errorCode) {
        delegate.markManual(operationId, leaseEpoch, errorCode);
    }

    @Override
    public List<ClaimedCommand> findExpiredInFlight(Instant now, int limit) {
        return delegate.findExpiredInFlight(now, limit);
    }

    @Override
    public boolean toReconciling(UUID operationId, Instant now, Instant reconcileAfter) {
        return delegate.toReconciling(operationId, now, reconcileAfter);
    }

    @Override
    public List<ClaimedCommand> findDueReconciling(Instant now, int limit) {
        return delegate.findDueReconciling(now, limit);
    }

    @Override
    public void reconcileConfirm(UUID operationId, String remoteId, String remoteUrl,
                                 PublicationResourceType resourceType, String marker,
                                 ExecutionEvent event) {
        delegate.reconcileConfirm(operationId, remoteId, remoteUrl, resourceType, marker, event);
    }

    @Override
    public void reconcileRetryWait(UUID operationId, Instant nextAttemptAt) {
        delegate.reconcileRetryWait(operationId, nextAttemptAt);
    }

    @Override
    public int reconcileUnknown(UUID operationId, Instant nextReconcileAfter) {
        return delegate.reconcileUnknown(operationId, nextReconcileAfter);
    }

    @Override
    public void reconcileManual(UUID operationId, String errorCode) {
        delegate.reconcileManual(operationId, errorCode);
    }

    @Override
    public List<ClaimedCommand> findStaleEpoch(int limit) {
        return delegate.findStaleEpoch(limit);
    }

    @Override
    public void supersedeStaleEpoch(UUID operationId) {
        delegate.supersedeStaleEpoch(operationId);
    }

    /** 让 ExecutionEventAppender 等未包装件可见（调试用） */
    PublicationStore delegate() {
        return delegate;
    }
}
