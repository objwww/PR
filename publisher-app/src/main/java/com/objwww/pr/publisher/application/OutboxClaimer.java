package com.objwww.pr.publisher.application;

import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.publisher.domain.port.PublicationStore;
import com.objwww.pr.publisher.domain.service.FencedPublicationExecutor;
import com.objwww.pr.publisher.domain.service.PublishOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Outbox 领取循环（§3.2，全局单 worker：MVP 刻意取舍 B-4，串行 = 自然保序）。
 *
 * <p>每轮：{@link PublicationStore#claim}（SKIP LOCKED + 短事务租约，立即提交）
 * → 逐条交 {@link FencedPublicationExecutor}。领取查询按 aggregate_key+sequence 排序，
 * 同 PR 内严格按序执行（跳号检测是兜底，E2）。
 *
 * <p>零 Spring 注解：bean 装配与 start/stop 生命周期由 config 的
 * {@code @Bean(initMethod/destroyMethod)} 驱动，且只在 docker profile 装配。
 */
public class OutboxClaimer {

    private static final Logger log = LoggerFactory.getLogger(OutboxClaimer.class);

    private final PublicationStore store;
    private final FencedPublicationExecutor executor;
    private final String ownerId;
    private final Duration leaseDuration;
    private final int batchSize;
    private final long idleSleepMs;
    private final long errorSleepMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public OutboxClaimer(PublicationStore store, FencedPublicationExecutor executor,
                         String ownerId, Duration leaseDuration, int batchSize,
                         long idleSleepMs, long errorSleepMs) {
        this.store = Objects.requireNonNull(store);
        this.executor = Objects.requireNonNull(executor);
        this.ownerId = Objects.requireNonNull(ownerId);
        this.leaseDuration = Objects.requireNonNull(leaseDuration);
        this.batchSize = batchSize;
        this.idleSleepMs = idleSleepMs;
        this.errorSleepMs = errorSleepMs;
    }

    /** 单轮领取+执行（测试与循环共用入口）；返回本轮处理条数 */
    public int runOnce() {
        List<ClaimedCommand> claimed = store.claim(ownerId, leaseDuration, batchSize);
        for (ClaimedCommand command : claimed) {
            try {
                PublishOutcome outcome = executor.execute(command);
                log.debug("outbox {} -> {}", command.operationId(), outcome);
            } catch (Exception e) {
                // 单条失败不阻塞整批；命令留在租约内，过期后由 scanner 收敛
                log.warn("执行 outbox 命令失败: {}", command.operationId(), e);
            }
        }
        return claimed.size();
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = Thread.ofVirtual().name("outbox-claimer").start(this::loop);
            log.info("OutboxClaimer 启动: owner={}", ownerId);
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
                log.error("OutboxClaimer 轮询异常", e);
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
