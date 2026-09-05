package com.objwww.pr.arena.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2-15 有界流量发生器：并发上限（同时活跃 ≤ 闸门值）、过载立即拒绝、
 * 停止后许可全量归还。同步用 CountDownLatch 表达状态，不靠固定 sleep 判定。
 */
class TrafficGeneratorTest {

    private TrafficGenerator generator;

    @AfterEach
    void stop() {
        if (generator != null) {
            generator.stop();
        }
    }

    @Test
    void concurrencyCapOverloadRejectionAndPermitReturn() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);

        TrafficGenerator.Journey blockingJourney = correlationId -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
        };

        generator = new TrafficGenerator(blockingJourney, 4, 60_000,
                new SimpleMeterRegistry());
        generator.start(); // 节奏循环 60s 间隔，测试期内不投递（确定性只来自显式投递）

        // 投 12 个旅程（闸门 4）：4 个在跑、其余立即拒绝
        int accepted = 0;
        for (int i = 0; i < 12; i++) {
            if (generator.submitOne()) {
                accepted++;
            }
        }
        // 等待闸门饱和（状态同步：最多等到 4 个活跃或全部投完）
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (generator.activeJourneys() < 4 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertThat(maxActive.get()).as("同时活跃旅程不得超过并发闸门").isLessThanOrEqualTo(4);
        assertThat(generator.rejectedCount()).as("闸门饱和后过载立即拒绝").isGreaterThan(0);
        assertThat(accepted + generator.rejectedCount()).isEqualTo(12);

        release.countDown();
        long stopDeadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (generator.activeJourneys() > 0 && System.nanoTime() < stopDeadline) {
            Thread.yield();
        }
        generator.stop();
        assertThat(generator.availablePermits()).as("停止后许可全量归还").isEqualTo(4);
    }

    @Test
    void invalidConcurrencyRejected() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                new TrafficGenerator(c -> {
                }, 0, 100, new SimpleMeterRegistry())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
