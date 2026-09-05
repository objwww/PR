package com.objwww.pr.notify.application;

import com.objwww.pr.notify.domain.model.ClaimedNotification;
import com.objwww.pr.notify.domain.port.NotifyOutboxStore;
import com.objwww.pr.notify.domain.service.FencedNotifyExecutor;
import com.objwww.pr.notify.domain.service.NotifySender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * notify_outbox 领取循环（M3-20；§6.8 全局单 worker——串行 = 渠道限流预算内的自然保序）。
 *
 * <p>每轮：{@link NotifyOutboxStore#claim}（SKIP LOCKED + 短事务租约，立即提交）
 * → 逐条交 {@link NotifySender}。单条失败不阻塞整批；429 持久化退避不占槽
 * （行回 RETRY_WAIT，worker 不睡眠等待，下一轮领取自然跳过未到期行）。
 * 零 Spring 注解：bean 装配与 start/stop 生命周期由 config 的
 * {@code @Bean(initMethod/destroyMethod)} 驱动。
 */
public class NotifyOutboxClaimer {

    private static final Logger log = LoggerFactory.getLogger(NotifyOutboxClaimer.class);

    private final NotifyOutboxStore store;
    private final NotifySender sender;
    private final String ownerId;
    private final Duration leaseDuration;
    private final int batchSize;
    private final long idleSleepMs;
    private final long errorSleepMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public NotifyOutboxClaimer(NotifyOutboxStore store, NotifySender sender,
                               String ownerId, Duration leaseDuration, int batchSize,
                               long idleSleepMs, long errorSleepMs) {
        this.store = Objects.requireNonNull(store);
        this.sender = Objects.requireNonNull(sender);
        this.ownerId = Objects.requireNonNull(ownerId);
        this.leaseDuration = Objects.requireNonNull(leaseDuration);
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize 从 1 起（有限批量，§6.8 预算）");
        }
        this.batchSize = batchSize;
        this.idleSleepMs = idleSleepMs;
        this.errorSleepMs = errorSleepMs;
    }

    /** 单轮领取+执行（测试与循环共用入口）；返回本轮处理条数 */
    public int runOnce() {
        List<ClaimedNotification> claimed = store.claim(ownerId, leaseDuration, batchSize);
        for (ClaimedNotification notification : claimed) {
            try {
                FencedNotifyExecutor.Outcome outcome = sender.execute(notification);
                log.debug("notify_outbox {} -> {}", notification.operationId(), outcome);
            } catch (Exception e) {
                // 渲染/路由之外的意外异常：行留在 CLAIMED，租约过期后由 claim 折叠回收
                log.warn("执行通知失败（行留租约待回收）: operation={}",
                        notification.operationId(), e);
            }
        }
        return claimed.size();
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = Thread.ofVirtual().name("notify-outbox-claimer").start(this::loop);
            log.info("NotifyOutboxClaimer 启动: owner={}", ownerId);
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
                log.error("notify 领取循环异常", e);
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
}
