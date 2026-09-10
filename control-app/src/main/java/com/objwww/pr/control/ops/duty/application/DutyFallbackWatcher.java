package com.objwww.pr.control.ops.duty.application;

import com.objwww.pr.control.infrastructure.observability.StructuredLog;
import com.objwww.pr.control.ops.duty.domain.DutyResolver;
import com.objwww.pr.control.ops.duty.domain.DutyScheduleSnapshot;
import com.objwww.pr.control.ops.duty.domain.DutyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 降级 watcher（M7-13；AM7 §3 DutyFallbackWatcher）：扫描 DEAD duty_delivery →
 * 条件推进次优先级通道补投（契约③：唯一键 (notification_id, priority) +
 * ON CONFLICT DO NOTHING = 并发安全；DEAD 是终态，重复扫描无害）。
 * 不重试（重试归 notify-app 租约面）。
 *
 * <p>期限闸（契约③ 最长通知期限）：通知龄超 {@code maxNotificationAge} 不再降级——
 * DUTY_DEGRADE_DEADLINE 台账事件。链耗尽（无更低优先级可投）：DUTY_CHAIN_EXHAUSTED
 * 台账事件（不变量 4 的 SUPPRESSED 台账面：全链 DEAD 行 + 事件可审计）。
 * 时钟 = Supplier（B-41 律）。
 */
public class DutyFallbackWatcher {

    private static final Logger log = LoggerFactory.getLogger(DutyFallbackWatcher.class);

    private final DutyStore store;
    private final Supplier<Instant> clock;
    private final Duration maxNotificationAge;
    private final Duration scanInterval;
    private final long errorSleepMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public DutyFallbackWatcher(DutyStore store, Supplier<Instant> clock,
                               Duration maxNotificationAge) {
        this(store, clock, maxNotificationAge, Duration.ofSeconds(30), 10_000L);
    }

    public DutyFallbackWatcher(DutyStore store, Supplier<Instant> clock,
                               Duration maxNotificationAge, Duration scanInterval,
                               long errorSleepMs) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
        this.maxNotificationAge = Objects.requireNonNull(maxNotificationAge);
        this.scanInterval = Objects.requireNonNull(scanInterval);
        this.errorSleepMs = errorSleepMs;
    }

    /** 单轮扫描（测试与循环共用入口）；返回本轮补投行数 */
    public int runOnce() {
        Instant now = clock.get();
        List<DutyStore.DeadDelivery> dead =
                store.findDeadDeliveriesUpdatedSince(now.minus(scanInterval.multipliedBy(2)));
        int acted = 0;
        if (dead.isEmpty()) {
            return 0;
        }
        DutyResolver.Resolution resolved = DutyResolver.resolve(now, store.loadSnapshot());
        List<DutyScheduleSnapshot.Channel> chain = resolved.channelChain();
        for (DutyStore.DeadDelivery row : dead) {
            if (now.isAfter(row.notificationCreatedAt().plus(maxNotificationAge))) {
                event("DUTY_DEGRADE_DEADLINE", row, "age_beyond_max");
                continue;
            }
            DutyScheduleSnapshot.Channel next = chain.stream()
                    .filter(c -> c.priority() > row.priority())
                    .min(java.util.Comparator.comparingInt(DutyScheduleSnapshot.Channel::priority))
                    .orElse(null);
            if (next == null) {
                event("DUTY_CHAIN_EXHAUSTED", row, "no_next_channel");
                continue;
            }
            if (store.insertDeliveryIfAbsent(row.notificationId(), next)) {
                acted++;
            }
        }
        return acted;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = Thread.ofVirtual().name("duty-fallback-watcher").start(this::loop);
            log.info("DutyFallbackWatcher 启动: interval={}s maxAge={}s",
                    scanInterval.toSeconds(), maxNotificationAge.toSeconds());
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
                runOnce();
                Thread.sleep(scanInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("duty 降级扫描异常", e);
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

    private void event(String name, DutyStore.DeadDelivery row, String reason) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("notification_id", row.notificationId().toString());
        fields.put("dead_priority", row.priority());
        fields.put("reason", reason);
        StructuredLog.event(log, name, fields);
    }
}
