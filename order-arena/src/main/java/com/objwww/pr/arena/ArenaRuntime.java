package com.objwww.pr.arena;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.ArrayList;
import java.util.List;

/**
 * 靶场运行时（docker 装配）：长活虚拟线程循环与启停型组件的统一生命周期
 * （SmartLifecycle）。循环纪律：先睡后跑（stop 快速收敛）、单轮异常吞掉只记日志
 * （DB 故障不死循环线程）、各循环独立（探测失明不影响补偿，反之亦然）。
 */
public final class ArenaRuntime implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ArenaRuntime.class);

    /** 单个周期循环（tick 在虚拟线程内串行执行） */
    public record Loop(String name, Runnable tick, long intervalMs) {
    }

    private final List<Loop> loops = new ArrayList<>();
    private final List<StartStoppable> startables = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    public ArenaRuntime(List<Loop> loops) {
        this.loops.addAll(loops);
    }

    public ArenaRuntime withStartable(StartStoppable startable) {
        startables.add(startable);
        return this;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        for (StartStoppable s : startables) {
            s.start();
        }
        for (Loop loop : loops) {
            threads.add(Thread.ofVirtual().name("arena-" + loop.name()).start(() -> {
                while (running) {
                    try {
                        Thread.sleep(loop.intervalMs());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!running) {
                        return;
                    }
                    try {
                        loop.tick().run();
                    } catch (RuntimeException e) {
                        log.warn("循环 {} 单轮失败（继续）: {}", loop.name(), e.getMessage());
                    }
                }
            }));
        }
        log.info("ArenaRuntime 启动: loops={} startables={}", loops.size(), startables.size());
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        for (StartStoppable s : startables) {
            s.stop();
        }
        for (Thread t : threads) {
            t.interrupt();
        }
        for (Thread t : threads) {
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
        log.info("ArenaRuntime 已停止");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // 最晚启动、最早停止（流量与循环不早于 web 端口就绪）
        return Integer.MAX_VALUE - 1;
    }
}
