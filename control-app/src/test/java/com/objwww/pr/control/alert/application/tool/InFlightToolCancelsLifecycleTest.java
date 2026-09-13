package com.objwww.pr.control.alert.application.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RV03 在飞事实生命周期（审查方案 C 批）：停止请求/等待结束/执行退出三态分离——
 * 调用方 finally 只结束等待；执行体忽略中断时在飞事实保留至执行 finally；
 * 排队取消=零执行；注册/删除按 Run 原子；墓碑显式 release。
 */
class InFlightToolCancelsLifecycleTest {

    private final InFlightToolCancels cancels = new InFlightToolCancels();

    @Test
    @DisplayName("T12：执行体忽略中断——endWait 后在飞事实保留，exit（执行 finally）才归零")
    void inflightFactSurvivesWaitEndUntilExecutorExit() throws Exception {
        UUID runId = UUID.randomUUID();
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var handle = cancels.register(runId);
            // FutureTask=生产 submit 同型（FutureTask.cancel 不抛；已终局 CompletableFuture
            // 的 cancel 会同步抛 CancellationException——interrupt 面另有防护）
            java.util.concurrent.FutureTask<Object> task = new java.util.concurrent.FutureTask<>(
                    () -> {
                        handle.beginRun();
                        release.await(10, TimeUnit.SECONDS); // 忽略中断的执行体形态
                        return null;
                    });
            pool.execute(task);
            handle.attach(task);
            cancels.cancelRun(runId); // 中断请求——执行体吞掉
            try {
                task.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.CancellationException futureCancelled) {
                // FutureTask 语义：cancel(true) 后 get 抛 CancellationException，
                // 但执行体可仍在运行——这正是 RV03 要分离的两个事实
            }
            cancels.endWait(runId, handle);

            assertThat(cancels.inflightCount(runId))
                    .as("等待已结束但执行体未退出：在飞事实不假报归零").isEqualTo(1);
            assertThat(task.isDone()).as("不能用 Future.isDone 冒充执行退出").isTrue();

            cancels.exit(handle); // 执行包装器 finally 的真实退出面
            assertThat(cancels.inflightCount(runId)).isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("T13：排队取消——beginRun 竞争获胜=零执行（CANCELLED_BEFORE_START），回收后零在飞")
    void queuedCancelWinsRacesZeroExecution() {
        UUID runId = UUID.randomUUID();
        var handle = cancels.register(runId);
        cancels.cancelRun(runId); // 取消先于 beginRun

        assertThat(handle.beginRun()).as("取消胜：执行体不启动").isFalse();
        assertThat(cancels.wasStopCancelled(runId)).isTrue();
        cancels.reclaim(runId, handle);
        assertThat(cancels.inflightCount(runId)).isZero();
    }

    @Test
    @DisplayName("T14：同 Run register/unregister 并发交错——不漏登、不误删新集合（按 Run 原子 compute）")
    void concurrentRegisterUnregisterKeepsConsistency() throws Exception {
        UUID runId = UUID.randomUUID();
        int threads = 8, rounds = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int r = 0; r < rounds; r++) {
                var h1 = CompletableFuture.supplyAsync(() -> cancels.register(runId), pool);
                var h2 = CompletableFuture.supplyAsync(() -> cancels.register(runId), pool);
                InFlightToolCancels.Handle a = h1.get(5, TimeUnit.SECONDS);
                InFlightToolCancels.Handle b = h2.get(5, TimeUnit.SECONDS);
                a.markExit();
                cancels.exit(a);   // 一个退出回收
                assertThat(cancels.inflightCount(runId))
                        .as("round %d：另一句柄不得被交错删除", r).isEqualTo(1);
                b.markExit();
                cancels.exit(b);
                assertThat(cancels.inflightCount(runId)).isZero();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("T15：墓碑显式 release——终态全静默后回收；新动作由持久终态检查挡（注册表面不冒充闸门）")
    void tombstoneReleasedExplicitlyNotByTtl() {
        UUID runId = UUID.randomUUID();
        var handle = cancels.register(runId);
        cancels.cancelRun(runId);
        assertThat(cancels.wasStopCancelled(runId)).isTrue();

        cancels.exit(handle);
        cancels.release(runId); // 持有全局知识的调用方在终态+全静默后释放

        assertThat(cancels.wasStopCancelled(runId)).isFalse();
        assertThat(cancels.inflightCount(runId)).isZero();
    }

    @Test
    @DisplayName("T12 补：cancelRun 到静默窗口内 inflightCount 真值（停止中读面口径）")
    void inflightCountTruthfulDuringStopWindow() throws Exception {
        UUID runId = UUID.randomUUID();
        AtomicInteger executions = new AtomicInteger();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var handle = cancels.register(runId);
            CompletableFuture.runAsync(() -> {
                if (handle.beginRun()) {
                    executions.incrementAndGet();
                }
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    // 忽略中断
                }
            }, pool);
            handle.attach(CompletableFuture.completedFuture(null));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            cancels.cancelRun(runId);

            assertThat(executions.get()).isEqualTo(1);
            assertThat(cancels.inflightCount(runId))
                    .as("停止中：执行体仍运行，在飞=1").isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
