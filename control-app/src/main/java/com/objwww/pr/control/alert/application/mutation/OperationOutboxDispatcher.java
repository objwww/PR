package com.objwww.pr.control.alert.application.mutation;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.mutation.OperationStatus;
import com.objwww.pr.control.alert.domain.mutation.RcaOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 派发循环（PB-B4，设计基线 §2.8 Dispatcher 面）：claim outbox（at-least-once，
 * 租约+SKIP LOCKED）→ 幂等派发（operation 当前态检查——已推进 = 跳过不重执）→
 * dry-run Runner 结局走状态机：
 *
 * <pre>
 * EXECUTED：       PREPARED/RETRYABLE → DISPATCHED → ACKNOWLEDGED → VERIFIED
 *                  （VERIFIED 即过释放闸释放资源锁）→ COMPLETED
 * TIMEOUT_UNKNOWN：→ UNKNOWN（锁保持 BUSY；RECONCILING 裁决归 B5 reconcile）
 * </pre>
 *
 * <p>崩溃语义：claim 后崩溃 → 租约过期回收重领（at-least-once）+ operation 幂等
 * （不重执已派发动作）；PREPARED 悬挂（outbox 无行/长期未派发）由 reconcile 扫描
 * 发现 ESCALATE（B5）。默认关闭（{@code app.alert.mutation.dispatcher-enabled:false}）。
 */
public class OperationOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OperationOutboxDispatcher.class);

    private final OperationOutboxStore outbox;
    private final OperationLedgerStore operations;
    private final ResourceLockStore locks;
    private final ActionRunner runner;
    private final ActionRunner realRunner; // PD-D1：dry_run=false 的真执行面（可空）
    private final RcaEventAppender events;
    private final String owner;
    private final Duration lease;
    private final Duration interval;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread workerThread;

    public OperationOutboxDispatcher(OperationOutboxStore outbox,
            OperationLedgerStore operations, ResourceLockStore locks, ActionRunner runner,
            RcaEventAppender events, String owner, Duration lease, Duration interval,
            Clock clock) {
        this(outbox, operations, locks, runner, null, events, owner, lease, interval, clock);
    }

    /** PD-D1 全参形态：realRunner 承接 dry_run=false 的真派发 */
    public OperationOutboxDispatcher(OperationOutboxStore outbox,
            OperationLedgerStore operations, ResourceLockStore locks, ActionRunner runner,
            ActionRunner realRunner, RcaEventAppender events, String owner, Duration lease,
            Duration interval, Clock clock) {
        this.outbox = Objects.requireNonNull(outbox);
        this.operations = Objects.requireNonNull(operations);
        this.locks = Objects.requireNonNull(locks);
        this.runner = Objects.requireNonNull(runner);
        this.realRunner = realRunner;
        this.events = Objects.requireNonNull(events);
        this.owner = Objects.requireNonNull(owner);
        this.lease = Objects.requireNonNull(lease);
        if (interval != null && (interval.isNegative() || interval.isZero())) {
            throw new IllegalArgumentException("dispatcher interval 必须为正（null=关闭）");
        }
        this.interval = interval;
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized void start() {
        if (interval == null) {
            log.info("OperationOutboxDispatcher 未配置间隔，派发循环关闭");
            return;
        }
        if (running.compareAndSet(false, true)) {
            workerThread = Thread.ofVirtual().name("operation-outbox-dispatcher").start(() -> {
                while (running.get()) {
                    try {
                        dispatchOnce();
                    } catch (RuntimeException e) {
                        log.error("派发轮失败，{} 后重试", interval, e);
                    }
                    try {
                        Thread.sleep(interval.toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
            log.info("OperationOutboxDispatcher 启动 interval={} owner={}", interval, owner);
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    /** 单轮派发（测试直调面）；返回处理条数 */
    public int dispatchOnce() {
        int handled = outbox.reclaimExpiredLeases(clock.instant());
        var claim = outbox.claimNext(owner, lease, clock.instant());
        if (claim.isEmpty()) {
            return handled;
        }
        handled++;
        dispatch(claim.get());
        return handled;
    }

    private void dispatch(OperationOutboxStore.Claimed claim) {
        RcaOperation operation = operations.findById(claim.operationId()).orElseThrow(
                () -> new IllegalStateException("outbox 指向不存在的 operation: "
                        + claim.operationId()));
        OperationStatus current = operation.status();
        if (current != OperationStatus.PREPARED && current != OperationStatus.RETRYABLE) {
            // 幂等：已派发/已推进（并发赢家或重投）——outbox 终态化，不重执
            outbox.markDispatched(claim.outboxId(), clock.instant());
            emit(operation, "OPERATION_DISPATCH_SKIPPED",
                    Map.of("from_status", current.name()));
            return;
        }
        // 派发（状态机当前态 CAS；败方 = 并发赢家已推进，幂等收尾）
        boolean advanced = operations.transition(operation.operationId(), current,
                OperationStatus.DISPATCHED, clock.instant());
        outbox.markDispatched(claim.outboxId(), clock.instant());
        if (!advanced) {
            emit(operation, "OPERATION_DISPATCH_SKIPPED", Map.of("from_status", current.name()));
            return;
        }
        emit(operation, "OPERATION_DISPATCHED", Map.of("resource_uid", operation.resourceUid()
                == null ? "" : operation.resourceUid()));
        // PD-D1：dry_run 分流——真执行面缺席时 UNKNOWN（reconcile → ESCALATED，
        // 不静默丢）；present 则真派发
        ActionRunner chosen = operation.dryRun() ? runner : realRunner;
        ActionRunner.Outcome outcome;
        if (chosen == null) {
            operations.transition(operation.operationId(), OperationStatus.DISPATCHED,
                    OperationStatus.UNKNOWN, clock.instant());
            emit(operation, "OPERATION_UNKNOWN",
                    Map.of("note", "REAL_EXECUTOR_ABSENT; 锁保持; reconcile 裁决"));
            return;
        }
        try {
            outcome = chosen.run(operation);
        } catch (RuntimeException e) {
            log.error("Runner 执行异常（按 timeout≠failed 处理）: op={}",
                    operation.operationId(), e);
            outcome = ActionRunner.Outcome.TIMEOUT_UNKNOWN;
        }
        walk(operation.operationId(), operation.resourceUid(), outcome);
    }

    private void walk(UUID operationId, String resourceUid, ActionRunner.Outcome outcome) {
        if (outcome == ActionRunner.Outcome.TIMEOUT_UNKNOWN) {
            if (operations.transition(operationId, OperationStatus.DISPATCHED,
                    OperationStatus.UNKNOWN, clock.instant())) {
                emitById(operationId, "OPERATION_UNKNOWN",
                        Map.of("note", "timeout!=failed; 锁保持 BUSY; reconcile 裁决"));
            }
            return; // 锁不动——RECONCILING 才有裁决权（B5）
        }
        // EXECUTED 全链：ACK → VERIFIED（释放闸）→ COMPLETED
        advance(operationId, OperationStatus.DISPATCHED, OperationStatus.ACKNOWLEDGED,
                "OPERATION_ACKNOWLEDGED");
        if (advance(operationId, OperationStatus.ACKNOWLEDGED, OperationStatus.VERIFIED,
                "OPERATION_VERIFIED") && resourceUid != null) {
            boolean released = locks.releaseOnTerminalState(resourceUid, operationId);
            emitById(operationId, "OPERATION_LOCK_RELEASED",
                    Map.of("released", String.valueOf(released)));
        }
        advance(operationId, OperationStatus.VERIFIED, OperationStatus.COMPLETED,
                "OPERATION_COMPLETED");
    }

    private boolean advance(UUID operationId, OperationStatus expected, OperationStatus next,
            String eventType) {
        boolean advanced = operations.transition(operationId, expected, next, clock.instant());
        if (advanced) {
            emitById(operationId, eventType, Map.of());
        }
        return advanced;
    }

    private void emit(RcaOperation operation, String type, Map<String, String> extra) {
        emitById(operation.operationId(), type, extra);
    }

    private void emitById(UUID operationId, String type, Map<String, String> extra) {
        var operation = operations.findById(operationId);
        UUID runId = operation.map(RcaOperation::runId).orElse(null);
        if (runId == null) {
            return;
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("kind", type);
        payload.put("operation_id", operationId.toString());
        payload.putAll(extra);
        events.append(runId, new RcaEventAppender.EventDraft(UUID.randomUUID(), type,
                OperationPlanner.CanonicalEventJson.canonicalize(payload)));
    }
}
