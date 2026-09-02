package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.ai.ModelCallLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 账本 Recovery（§4.6）：启动时必扫一次 + 周期扫描，
 * `STARTED` 且 `started_at < now - recovery-after` → 标 UNKNOWN（幂等条件更新）。
 *
 * <p>UNKNOWN 是终态，永不再改写为成功/失败——诚实表达"可能已付费，结果未知"（R-M1）。
 * 风格同 WorkItemWorker：零注解 worker，init/destroy 驱动虚拟线程循环（项目惯例，
 * 不引 @Scheduled）。
 */
public class ModelCallLedgerRecovery {

    private static final Logger log = LoggerFactory.getLogger(ModelCallLedgerRecovery.class);

    private final ModelCallLedgerRepository repository;
    private final Duration recoveryAfter;
    private final long scanIntervalMs;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public ModelCallLedgerRecovery(ModelCallLedgerRepository repository,
                                   Duration recoveryAfter, long scanIntervalMs) {
        this.repository = Objects.requireNonNull(repository);
        this.recoveryAfter = Objects.requireNonNull(recoveryAfter);
        if (scanIntervalMs <= 0) {
            throw new IllegalArgumentException("scanIntervalMs 必须为正: " + scanIntervalMs);
        }
        this.scanIntervalMs = scanIntervalMs;
    }

    /** 启动：先必扫一次，再起周期循环（initMethod）。 */
    public void start() {
        scanOnce();
        if (running.compareAndSet(false, true)) {
            thread = Thread.ofVirtual().name("model-call-ledger-recovery").start(this::loop);
            log.info("ModelCallLedgerRecovery 启动: recoveryAfter={} scanIntervalMs={}",
                    recoveryAfter, scanIntervalMs);
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
                Thread.sleep(scanIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running.get()) {
                return;
            }
            scanOnce();
        }
    }

    /** 扫描一轮；自身失败不致命（下次周期继续，标 UNKNOWN 幂等）。 */
    private void scanOnce() {
        try {
            Instant threshold = Instant.now().minus(recoveryAfter);
            int marked = repository.markUnknownOlderThan(threshold);
            if (marked > 0) {
                log.warn("标记 {} 行超龄 STARTED 账本为 UNKNOWN（threshold={}）", marked, threshold);
            }
        } catch (RuntimeException e) {
            log.warn("账本 Recovery 扫描失败（下周期重试）: {}", e.getClass().getSimpleName());
        }
    }
}
