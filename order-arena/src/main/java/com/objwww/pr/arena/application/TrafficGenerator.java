package com.objwww.pr.arena.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 有界流量发生器（M2-15，§7.2）：虚拟线程一任务一线程（JEP 444，不做固定池），
 * 并发闸门 = Semaphore(默认 4)（不是线程池大小）；过载立即拒绝（不排队）；
 * 停止后许可全量归还、无无界线程。
 *
 * <p>只产生 live- 正常流量（chaos- 流量属于故障注入/E2E 专供）；旅程 = 创单 → 支付
 * →（确定性散列命中约 1/3 时）取消。业务计数走 micrometer（流量事实，非故障自报）。
 */
public class TrafficGenerator implements com.objwww.pr.arena.StartStoppable {

    /** 一次业务旅程（装配期绑定 TwoStepOrderService 适配；测试绑假实现） */
    public interface Journey {
        void run(String correlationId);
    }

    private final Journey journey;
    private final int concurrency;
    private final long intervalMs;
    private final Semaphore permits;
    private final Counter submittedCounter;
    private final Counter rejectedCounter;

    private final AtomicInteger active = new AtomicInteger();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private volatile boolean running;
    private Thread loopThread;

    public TrafficGenerator(Journey journey, int concurrency, long intervalMs,
                            MeterRegistry registry) {
        if (concurrency <= 0) {
            throw new IllegalArgumentException("并发闸门必须为正");
        }
        this.journey = journey;
        this.concurrency = concurrency;
        this.intervalMs = intervalMs;
        this.permits = new Semaphore(concurrency);
        this.submittedCounter = Counter.builder("arena_traffic_submitted_total")
                .description("已投递的正常流量旅程数").register(registry);
        this.rejectedCounter = Counter.builder("arena_traffic_rejected_total")
                .description("并发闸门拒绝次数").register(registry);
    }

    /** 启动节奏循环（单守护线程：只负责投递，旅程在各自的虚拟线程执行；先睡后投） */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        loopThread = Thread.ofVirtual().name("traffic-gen-loop").start(() -> {
            while (running) {
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!running) {
                    return;
                }
                submitOne();
            }
        });
    }

    /** 投递一次旅程：过载立即拒绝（tryAcquire，不等待） */
    public boolean submitOne() {
        if (!running) {
            return false;
        }
        if (!permits.tryAcquire()) {
            rejected.incrementAndGet();
            rejectedCounter.increment();
            return false;
        }
        String correlationId = "live-" + UUID.randomUUID();
        submitted.incrementAndGet();
        submittedCounter.increment();
        Thread.ofVirtual().name("traffic-journey").start(() -> {
            active.incrementAndGet();
            try {
                journey.run(correlationId);
            } catch (RuntimeException e) {
                // 旅程失败不影响发生器寿命（故障场景下业务本就会失败）
            } finally {
                active.decrementAndGet();
                permits.release();
            }
        });
        return true;
    }

    /** 停止：停止投递并等待循环退出；许可在每旅程 finally 中归还（无泄漏） */
    public void stop() {
        running = false;
        if (loopThread != null) {
            loopThread.interrupt();
            try {
                loopThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    public int concurrencyLimit() {
        return concurrency;
    }

    public long rejectedCount() {
        return rejected.get();
    }

    public long submittedCount() {
        return submitted.get();
    }

    public int activeJourneys() {
        return active.get();
    }
}
