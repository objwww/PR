package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PA-A2（V112）：每日验链作业——对全部有事件的 run 重算哈希链并比对（R9 口径：
 * tamper-evident under the assumed DB write boundary）。任一断链 = ERROR 日志 +
 * 结构化事件外发（不自动修复、不猜、不删——审计义务移交人工），逐 run 独立推进
 * （单 run 异常不阻断后续，与 RunReconciler 同律）。
 *
 * <p>调度形态：独立看门狗虚拟线程，{@code app.alert.event-chain.verify-interval}
 * （缺省 PT24H；非正值 = 关闭）。轻负载查询（事件行只读 digest/hash 列），与业务
 * 事务无锁交叉。
 */
public class EventChainVerifyLoop {

    private static final Logger log = LoggerFactory.getLogger(EventChainVerifyLoop.class);

    private final RcaEventAppender events;
    private final Duration interval;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread workerThread;

    public EventChainVerifyLoop(RcaEventAppender events, Duration interval) {
        this.events = Objects.requireNonNull(events, "events");
        if (interval != null && (interval.isNegative() || interval.isZero())) {
            throw new IllegalArgumentException("verify-interval 必须为正（null=关闭）");
        }
        this.interval = interval;
    }

    public synchronized void start() {
        if (interval == null) {
            log.info("EventChainVerifyLoop 未配置间隔，验链作业关闭");
            return;
        }
        if (running.compareAndSet(false, true)) {
            workerThread = Thread.ofVirtual().name("event-chain-verify").start(() -> {
                while (running.get()) {
                    try {
                        verifyOnce();
                    } catch (RuntimeException e) {
                        log.error("验链整轮失败，{} 后重试", interval, e);
                    }
                    try {
                        Thread.sleep(interval.toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
            log.info("EventChainVerifyLoop 启动 interval={}", interval);
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    /** 单轮验链（测试直调面）；返回断链 run 数（0 = 全绿） */
    public int verifyOnce() {
        int broken = 0;
        List<UUID> runIds = events.runIdsWithEvents();
        for (UUID runId : runIds) {
            try {
                RcaEventAppender.ChainReport report = events.verifyChain(runId);
                if (!report.ok()) {
                    broken++;
                    log.error("事件哈希链断裂（审计义务移交人工，不自动修复）: run={} "
                                    + "events={} verified={} brokenAtSeq={}",
                            runId, report.events(), report.verified(), report.brokenAtSeq());
                }
            } catch (RuntimeException e) {
                log.error("run {} 验链读取失败（越过，下轮重试）", runId, e);
            }
        }
        if (broken == 0) {
            log.info("验链全绿: runs={} ", runIds.size());
        }
        return broken;
    }
}
