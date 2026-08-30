package com.objwww.pr.publisher.application;

import com.objwww.pr.publisher.domain.handler.PublicationHandler;
import com.objwww.pr.publisher.domain.handler.ReconcileVerdict;
import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.publisher.domain.port.PublicationStore;
import com.objwww.pr.publisher.domain.service.FencedPublicationExecutor;
import com.objwww.pr.publisher.domain.service.RetryBackoff;
import com.objwww.pr.shared.ExecutionEvent;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.CommandType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publication Reconciler（评审修正 #1，§4.3 崩溃收敛的真正执行者）。
 *
 * <p>三条扫描路径（每轮 runOnce 依次执行）：
 * <ol>
 *   <li>过期 IN_FLIGHT（lease_until &lt; now）→ RECONCILING：孤儿副作用防护，
 *       禁盲目重发（I7）；</li>
 *   <li>到期 RECONCILING → 经 {@link FencedPublicationExecutor#reconcile} 探测
 *       （触网只经 executor，I4）：找到 → CONFIRMED 不重复创建；窗口内穷尽 →
 *       RETRY_WAIT 安全重发；查不到也不能确认 → 计数 +1，超预算 → MANUAL 熔断（EX-04）；</li>
 *   <li>supersede 兜底（v2.1 修订三）：PENDING/RETRY_WAIT 且 epoch 落后 → 同事务
 *       SUPERSEDED + 级联（OPTIONAL 不级联，E3）+ 游标推进；IN_FLIGHT 不级联（先对账）。</li>
 * </ol>
 * 零 Spring 注解，只在 docker profile 装配。
 */
public class OutboxRecoveryScanner {

    private static final Logger log = LoggerFactory.getLogger(OutboxRecoveryScanner.class);
    private static final String PRODUCER = "publisher-app";

    private final PublicationStore store;
    private final FencedPublicationExecutor executor;
    private final Map<CommandType, PublicationHandler> handlers;
    private final RetryBackoff backoff = new RetryBackoff();
    private final Duration unknownRetryDelay;
    private final int maxReconcileNotFound;
    private final int scanLimit;
    private final long idleSleepMs;
    private final long errorSleepMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public OutboxRecoveryScanner(PublicationStore store, FencedPublicationExecutor executor,
                                 List<PublicationHandler> handlerList,
                                 Duration unknownRetryDelay, int maxReconcileNotFound,
                                 int scanLimit, long idleSleepMs, long errorSleepMs) {
        this.store = Objects.requireNonNull(store);
        this.executor = Objects.requireNonNull(executor);
        this.unknownRetryDelay = Objects.requireNonNull(unknownRetryDelay);
        this.maxReconcileNotFound = maxReconcileNotFound;
        this.scanLimit = scanLimit;
        this.idleSleepMs = idleSleepMs;
        this.errorSleepMs = errorSleepMs;
        this.handlers = new EnumMap<>(CommandType.class);
        for (PublicationHandler handler : handlerList) {
            this.handlers.put(handler.commandType(), handler);
        }
    }

    /** 单轮三路扫描；返回处理条数（测试与循环共用入口） */
    public int runOnce() {
        Instant now = Instant.now();
        return sweepExpiredInFlight(now) + driveReconciling(now) + sweepStaleEpoch();
    }

    /** 路径①：过期 IN_FLIGHT → RECONCILING */
    private int sweepExpiredInFlight(Instant now) {
        int done = 0;
        for (ClaimedCommand command : store.findExpiredInFlight(now, scanLimit)) {
            // 立即到期：下一轮路径②即探测
            if (store.toReconciling(command.operationId().value(), now, now)) {
                log.info("过期 IN_FLIGHT 转 RECONCILING: {}", command.operationId());
                done++;
            }
        }
        return done;
    }

    /** 路径②：到期 RECONCILING → 探测归类 */
    private int driveReconciling(Instant now) {
        int done = 0;
        for (ClaimedCommand command : store.findDueReconciling(now, scanLimit)) {
            try {
                settleReconcile(command, executor.reconcile(command), now);
                done++;
            } catch (Exception e) {
                log.warn("reconcile 失败（下轮重试）: {}", command.operationId(), e);
            }
        }
        return done;
    }

    private void settleReconcile(ClaimedCommand command, ReconcileVerdict verdict, Instant now) {
        UUID id = command.operationId().value();
        switch (verdict.kind()) {
            case FOUND -> {
                PublicationHandler handler = handlers.get(command.commandType());
                store.reconcileConfirm(id, verdict.remoteId(), verdict.remoteUrl(),
                        handler.resourceType(), handler.resourceMarker(command),
                        event(command, ExecutionEventType.PUBLICATION_CONFIRMED, Map.of(
                                "operation_id", command.operationId().toString(),
                                "remote_id", verdict.remoteId(),
                                "via", "reconcile")));
            }
            case NOT_FOUND ->
                // 窗口内穷尽确认不存在：退避后安全重发（§4.3）
                    store.reconcileRetryWait(id,
                            backoff.nextAttemptAt(command.attemptCount() + 1, now));
            case UNKNOWN -> {
                int notFound = store.reconcileUnknown(id, now.plus(unknownRetryDelay));
                if (notFound > maxReconcileNotFound) {
                    // EX-04：超对账预算熔断，不无限翻页
                    store.reconcileManual(id, "RECONCILE_BUDGET_EXCEEDED");
                    log.warn("reconcile 超预算熔断 MANUAL: {}", command.operationId());
                }
            }
            case MANUAL_POLICY -> store.reconcileManual(id, "REMOTE_NOT_FOUND");
        }
    }

    /** 路径③：supersede 兜底（epoch 落后于 subject 当前值的 PENDING/RETRY_WAIT） */
    private int sweepStaleEpoch() {
        int done = 0;
        for (ClaimedCommand command : store.findStaleEpoch(scanLimit)) {
            store.supersedeStaleEpoch(command.operationId().value());
            done++;
        }
        return done;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = Thread.ofVirtual().name("outbox-recovery-scanner").start(this::loop);
            log.info("OutboxRecoveryScanner 启动");
        }
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void loop() {
        while (running.get()) {
            try {
                if (runOnce() == 0) {
                    Thread.sleep(idleSleepMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("OutboxRecoveryScanner 轮询异常", e);
                sleepQuietly(errorSleepMs);
            }
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ExecutionEvent event(ClaimedCommand command, ExecutionEventType type, Map<String, Object> payload) {
        return new ExecutionEvent(UUID.randomUUID(), command.reviewRunId(), command.prRevisionId(),
                null, null, type, 1, null, command.reviewRunId(), PRODUCER, payload, Instant.now());
    }
}
