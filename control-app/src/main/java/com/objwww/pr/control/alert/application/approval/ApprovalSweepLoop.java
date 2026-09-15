package com.objwww.pr.control.alert.application.approval;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 审批过期清扫循环（PC-C1，§2.6 fail-closed 兜底）：PENDING 超过 300s 独立时钟
 * → EXPIRED；ACTIVE grant 过期 → EXPIRED。通知失败 fail-closed（5s/15s/45s×3 →
 * notify_failed）随 AM8 管理面接入前，本清扫是审批面唯一的超时终态执行者。
 */
public class ApprovalSweepLoop {

    private static final Logger log = LoggerFactory.getLogger(ApprovalSweepLoop.class);

    private final ApprovalStore store;
    private final RcaEventAppender events;
    private final Duration interval;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread workerThread;

    public ApprovalSweepLoop(ApprovalStore store, RcaEventAppender events, Duration interval,
            Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.events = Objects.requireNonNull(events);
        if (interval != null && (interval.isNegative() || interval.isZero())) {
            throw new IllegalArgumentException("sweep interval 必须为正（null=关闭）");
        }
        this.interval = interval;
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized void start() {
        if (interval == null) {
            log.info("ApprovalSweepLoop 未配置间隔，审批清扫关闭");
            return;
        }
        if (running.compareAndSet(false, true)) {
            workerThread = Thread.ofVirtual().name("approval-sweep").start(() -> {
                while (running.get()) {
                    try {
                        sweepOnce();
                    } catch (RuntimeException e) {
                        log.error("审批清扫轮失败，{} 后重试", interval, e);
                    }
                    try {
                        Thread.sleep(interval.toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
            log.info("ApprovalSweepLoop 启动 interval={}", interval);
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    /** 单轮清扫（测试直调面）；返回终态化数量 */
    public int sweepOnce() {
        int handled = 0;
        for (ApprovalStore.ExpiredRequest expired : store.expirePendingRequests(clock.instant())) {
            emit(expired.runId(), "APPROVAL_EXPIRED", Map.of(
                    "request_id", expired.requestId().toString(), "reason", "REQUEST_TTL"));
            handled++;
        }
        for (ApprovalStore.ExpiredRequest expired : store.expireActiveGrants(clock.instant())) {
            emit(expired.runId(), "GRANT_EXPIRED", Map.of(
                    "grant_id", expired.requestId().toString()));
            handled++;
        }
        return handled;
    }

    private void emit(UUID runId, String type, Map<String, String> extra) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("kind", type);
        payload.putAll(extra);
        events.append(runId, new RcaEventAppender.EventDraft(UUID.randomUUID(), type,
                com.objwww.pr.control.alert.application.mutation.OperationPlanner
                        .CanonicalEventJson.canonicalize(payload)));
    }
}
