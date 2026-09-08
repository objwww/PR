package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Holmes Shadow 调度循环（M6-05，C-65 持久工作面）：周期 SKIP LOCKED 批量认领
 * V34 工作行并委托 {@link HolmesShadowWorker} 逐行处理。认领即租约（默认
 * PT15M &gt; Holmes 最长在途窗）；进程崩溃后过期租约由下一轮认领回收（attempts
 * 有界）。单线程 daemon，随 alertFlowLifecycle 启停。
 */
public class HolmesShadowScheduler {

    private static final Logger log = LoggerFactory.getLogger(HolmesShadowScheduler.class);

    private final HolmesShadowWorkRepository works;
    private final HolmesShadowWorker worker;
    private final AlertClock clock;
    private final String owner;
    private final Duration lease;
    private final Duration pollInterval;
    private final int batchSize;
    private volatile ScheduledExecutorService executor;

    public HolmesShadowScheduler(HolmesShadowWorkRepository works, HolmesShadowWorker worker,
            AlertClock clock, String owner, Duration lease, Duration pollInterval,
            int batchSize) {
        this.works = Objects.requireNonNull(works, "works");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batch-size 从 1 起");
        }
        this.batchSize = batchSize;
    }

    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "holmes-shadow-scheduler");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::pollSafely, pollInterval.toMillis(),
                pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("HolmesShadowScheduler 已启动 poll={} lease={} batch={}",
                pollInterval, lease, batchSize);
    }

    public synchronized void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        executor = null;
    }

    /** 单轮认领-处理（包内可见，测试直驱）。异常吞并保循环存活（下一轮再认领）。 */
    void pollSafely() {
        try {
            pollOnce();
        } catch (RuntimeException e) {
            log.warn("Holmes shadow 轮询异常（循环继续）: {}", String.valueOf(e.getMessage()));
        }
    }

    /** 认领并处理一批；返回处理行数（测试断言面） */
    public int pollOnce() {
        List<HolmesShadowWorkRepository.ShadowWorkRow> batch =
                works.claimBatch(owner, clock.now(), lease, batchSize);
        for (var row : batch) {
            worker.process(row);
        }
        return batch.size();
    }
}
