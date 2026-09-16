package com.objwww.pr.control.alert.application.mutation;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.mutation.OperationStatus;
import com.objwww.pr.control.alert.domain.mutation.RcaOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * mutation 对账循环（PB-B5，设计基线 §1/§2.8/§2.9/§3.3）：
 *
 * <ul>
 *   <li><b>TTL 孤儿化驱动</b>：HELD 过期 → ORPHANED（§2.9：不让渡，只驱动本循环）；</li>
 *   <li><b>UNKNOWN → RECONCILING → 裁决</b>：中间态显式穿越（网络 timeout ≠ failed；
 *       VERIFIED（确认事实在档，释放闸放锁）/ RETRYABLE（outbox 同行回 PENDING
 *       重派，锁保持）/ ESCALATED（锁保持至人工裁决，AM8 出口））；</li>
 *   <li><b>PREPARED 悬挂 ESCALATE</b>：§2.8「consume 后 dispatch 长期未发生——
 *       reconcile 扫描发现并 ESCALATE，不静默丢、不自动重执」；</li>
 *   <li><b>reconcile 先于 reschedule</b>：本循环是唯一把 UNKNOWN/RECONCILING 推到
 *       终态/重派的面——在 Phase D 真实 mutation 存在时，Worker/Run 层任何重派
 *       决策必须以本循环产出的终态为前置（闸口 {@link MutationActiveGate}）。</li>
 * </ul>
 */
public class OperationReconciler {

    private static final Logger log = LoggerFactory.getLogger(OperationReconciler.class);

    /** dry-run 裁决策略（真实 resource_probe 随 Phase D；VERIFIED=事实确认在档） */
    public enum Verdict { VERIFIED, RETRYABLE }

    private final OperationLedgerStore operations;
    private final OperationOutboxStore outbox;
    private final ResourceLockStore locks;
    private final RcaEventAppender events;
    private final Verdict verdict;
    private final Duration hangingThreshold;
    private final Duration interval;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread workerThread;

    public OperationReconciler(OperationLedgerStore operations, OperationOutboxStore outbox,
            ResourceLockStore locks, RcaEventAppender events, Verdict verdict,
            Duration hangingThreshold, Duration interval, Clock clock) {
        this.operations = Objects.requireNonNull(operations);
        this.outbox = Objects.requireNonNull(outbox);
        this.locks = Objects.requireNonNull(locks);
        this.events = Objects.requireNonNull(events);
        this.verdict = Objects.requireNonNull(verdict);
        this.hangingThreshold = Objects.requireNonNull(hangingThreshold);
        if (interval != null && (interval.isNegative() || interval.isZero())) {
            throw new IllegalArgumentException("reconciler interval 必须为正（null=关闭）");
        }
        this.interval = interval;
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized void start() {
        if (interval == null) {
            log.info("OperationReconciler 未配置间隔，mutation 对账循环关闭");
            return;
        }
        if (running.compareAndSet(false, true)) {
            workerThread = Thread.ofVirtual().name("operation-reconciler").start(() -> {
                while (running.get()) {
                    try {
                        reconcileOnce();
                    } catch (RuntimeException e) {
                        log.error("mutation 对账轮失败，{} 后重试", interval, e);
                    }
                    try {
                        Thread.sleep(interval.toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
            log.info("OperationReconciler 启动 interval={} verdict={} hanging={}",
                    interval, verdict, hangingThreshold);
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    /** 单轮对账（测试直调面）：孤儿化 + UNKNOWN 走链 + PREPARED 悬挂；返回处理数 */
    public int reconcileOnce() {
        int handled = locks.markOrphanedExpired(clock.instant());
        for (UUID id : operations.idsInStatus(OperationStatus.UNKNOWN)) {
            handled += reconcileUnknown(id);
        }
        handled += escalateHangingPrepared();
        return handled;
    }

    private int reconcileUnknown(UUID operationId) {
        var operation = operations.findById(operationId).orElse(null);
        if (operation == null || operation.status() != OperationStatus.UNKNOWN) {
            return 0; // 并发赢家已推进
        }
        if (!operations.transition(operationId, OperationStatus.UNKNOWN,
                OperationStatus.RECONCILING, clock.instant())) {
            return 0;
        }
        emit(operation, "OPERATION_RECONCILING");
        // PD-D1 纪律：真执行（dry_run=false）的 UNKNOWN 不得被 dry-run 裁决自动
        // VERIFIED——无真实 resource_probe 前一律 ESCALATED 人工裁决（不猜）
        if (!operation.dryRun()) {
            if (operations.transition(operationId, OperationStatus.RECONCILING,
                    OperationStatus.ESCALATED, clock.instant())) {
                emit(operation, "OPERATION_ESCALATED",
                        Map.of("reason", "REAL_EXECUTION_NO_PROBE"));
            }
            return 1;
        }
        return switch (verdict) {
            case VERIFIED -> {
                if (operations.transition(operationId, OperationStatus.RECONCILING,
                        OperationStatus.VERIFIED, clock.instant())) {
                    emit(operation, "OPERATION_RECONCILED_VERIFIED");
                    boolean released = locks.releaseOnTerminalState(operation.resourceUid(),
                            operationId);
                    emit(operation, "OPERATION_LOCK_RELEASED",
                            Map.of("released", String.valueOf(released)));
                    operations.transition(operationId, OperationStatus.VERIFIED,
                            OperationStatus.COMPLETED, clock.instant());
                    emit(operation, "OPERATION_COMPLETED");
                }
                yield 1;
            }
            case RETRYABLE -> {
                if (operations.transition(operationId, OperationStatus.RECONCILING,
                        OperationStatus.RETRYABLE, clock.instant())) {
                    emit(operation, "OPERATION_RETRYABLE");
                    outbox.backToPending(operationId, clock.instant()); // 重派，锁保持
                }
                yield 1;
            }
        };
    }

    /** §2.8 PREPARED 悬挂：超阈值未派发 → ESCALATED（锁保持至人工裁决） */
    private int escalateHangingPrepared() {
        int handled = 0;
        for (UUID id : operations.idsInStatus(OperationStatus.PREPARED)) {
            var operation = operations.findById(id).orElse(null);
            if (operation == null || operation.status() != OperationStatus.PREPARED) {
                continue;
            }
            boolean noOutboxRow = outbox.findByOperation(id).isEmpty();
            Instant deadline = operation.preparedAt() == null
                    ? operation.createdAt() : operation.preparedAt();
            boolean hanging = noOutboxRow || deadline
                    .isBefore(clock.instant().minus(hangingThreshold));
            if (!hanging) {
                continue;
            }
            if (operations.transition(id, OperationStatus.PREPARED,
                    OperationStatus.ESCALATED, clock.instant())) {
                emit(operation, "OPERATION_ESCALATED",
                        Map.of("reason", noOutboxRow ? "NO_OUTBOX_ROW" : "DISPATCH_HANGING"));
                handled++;
            }
        }
        return handled;
    }

    private void emit(RcaOperation operation, String type, Map<String, String> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", type);
        payload.put("operation_id", operation.operationId().toString());
        payload.putAll(extra);
        events.append(operation.runId(), new RcaEventAppender.EventDraft(UUID.randomUUID(),
                type, OperationPlanner.CanonicalEventJson.canonicalize(payload)));
    }

    private void emit(RcaOperation operation, String type) {
        emit(operation, type, Map.of());
    }
}
