package com.objwww.pr.control.alert.application.tool;

import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;
import com.objwww.pr.control.alert.domain.tool.ToolPolicy;
import com.objwww.pr.control.alert.domain.tool.ToolRisk;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * ToolGateway 穷举单测（AM4 M4-16/17）：双闸鉴权（执行时仍拒）、R2/R3 VALIDATE_ONLY
 * 零执行、未声明参数 INVALID_ARGS、清单裁剪、硬 deadline + cancel、结果上限
 * RESULT_OVERSIZE、错误两族映射（脱敏固定文案）。
 */
class ToolGatewayTest {

    private static final UUID RUN = new UUID(0L, 42L);
    private static final Clock FIXED = Clock.fixed(Instant.ofEpochMilli(1_000_000),
            ZoneOffset.UTC);
    /** 共享单线程调用池（除超时案外都不阻塞；超时案自建自关） */
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    @AfterAll
    static void shutdownPool() {
        POOL.shutdownNow();
    }

    private final AtomicInteger remoteCalls = new AtomicInteger();

    private static ToolDefinition definition(String name, ToolRisk risk, long timeout,
            long resultLimit) {
        return new ToolDefinition(name, "1.0.0",
                Map.of("type", "object",
                        "properties", Map.of("q", Map.of("type", "string"))),
                risk, timeout, resultLimit);
    }

    private ToolGateway gateway(ToolRegistry.Registration registration, ToolPolicy policy,
            ExecutorService pool) {
        return new ToolGateway(new ToolRegistry(List.of(registration)), policy, pool, FIXED,
                null);
    }

    private ToolGateway.ToolInvocation invocation(String tool, Map<String, Object> args) {
        return new ToolGateway.ToolInvocation(RUN, RUN, RUN, 1, tool, "1.0.0",
                "2026-09-07T00:00:00Z/2026-09-07T01:00:00Z", args, null);
    }

    private ToolExecutor countingExecutor() {
        return execution -> {
            remoteCalls.incrementAndGet();
            return new byte[1];
        };
    }

    @Test
    void utW01_未知工具_UNKNOWN_TOOL() {
        ToolGateway gateway = gateway(
                new ToolRegistry.Registration(definition("known.tool", ToolRisk.R0, 1_000, 16),
                        countingExecutor()),
                new ToolPolicy(Set.of("known.tool")), POOL);
        assertThatThrownBy(() -> gateway.invoke(invocation("unknown.tool", Map.of())))
                .isInstanceOfSatisfying(ToolControlPlaneException.class,
                        e -> assertThat(e.reason()).isEqualTo(ToolControlReason.UNKNOWN_TOOL));
    }

    @Test
    void utW02_执行时二次鉴权_POLICY_DENIED_即便清单被绕过() {
        ToolGateway gateway = gateway(
                new ToolRegistry.Registration(definition("secret.tool", ToolRisk.R0, 1_000, 16),
                        countingExecutor()),
                new ToolPolicy(Set.of("other.tool")), POOL);
        assertThat(gateway.manifestFor()).isEmpty(); // 第一闸：清单裁剪
        assertThatThrownBy(() -> gateway.invoke(invocation("secret.tool", Map.of("q", "x"))))
                .isInstanceOfSatisfying(ToolControlPlaneException.class,
                        e -> assertThat(e.reason()).isEqualTo(ToolControlReason.POLICY_DENIED));
        assertThat(remoteCalls.get()).isZero(); // 第二闸：执行零发生
    }

    @Test
    void utW03_R2R3策略允许也只VALIDATE_ONLY_零执行() {
        ToolGateway gateway = gateway(
                new ToolRegistry.Registration(definition("write.tool", ToolRisk.R2, 1_000, 16),
                        countingExecutor()),
                new ToolPolicy(Set.of("write.tool")), POOL);
        ToolGateway.ToolInvocationResult result =
                gateway.invoke(invocation("write.tool", Map.of("q", "x")));
        assertThat(result.kind()).isEqualTo(ToolGateway.ToolInvocationResult.Kind.VALIDATE_ONLY);
        assertThat(result.body()).isNull();
        assertThat(result.actionDigest()).hasSize(64);
        assertThat(remoteCalls.get()).isZero();
    }

    @Test
    void utW04_未声明参数_INVALID_ARGS() {
        ToolGateway gateway = gateway(
                new ToolRegistry.Registration(definition("t.tool", ToolRisk.R0, 1_000, 16),
                        countingExecutor()),
                new ToolPolicy(Set.of("t.tool")), POOL);
        assertThatThrownBy(() -> gateway.invoke(
                invocation("t.tool", Map.of("q", "x", "evil", "payload"))))
                .isInstanceOfSatisfying(ToolControlPlaneException.class,
                        e -> assertThat(e.reason()).isEqualTo(ToolControlReason.INVALID_ARGS));
        assertThat(remoteCalls.get()).isZero();
    }

    @Test
    void utW05_成功路径_EXECUTED_digest稳定_清单裁剪生效() {
        ToolDefinition other = definition("other.tool", ToolRisk.R0, 1_000, 16);
        ToolGateway gateway = new ToolGateway(
                new ToolRegistry(List.of(
                        new ToolRegistry.Registration(
                                definition("t.tool", ToolRisk.R0, 1_000, 16),
                                countingExecutor()),
                        new ToolRegistry.Registration(other, countingExecutor()))),
                new ToolPolicy(Set.of("t.tool")), POOL, FIXED, null);
        assertThat(gateway.manifestFor()).hasSize(1); // 被拒工具从清单删除
        Map<String, Object> argsA = Map.of("q", "up");
        Map<String, Object> argsB = new java.util.TreeMap<>(argsA); // 不同字段序
        ToolGateway.ToolInvocationResult r1 = gateway.invoke(invocation("t.tool", argsA));
        ToolGateway.ToolInvocationResult r2 = gateway.invoke(invocation("t.tool", argsB));
        assertThat(r1.kind()).isEqualTo(ToolGateway.ToolInvocationResult.Kind.EXECUTED);
        assertThat(r1.actionDigest()).isEqualTo(r2.actionDigest());
        assertThat(remoteCalls.get()).isEqualTo(2);
    }

    @Test
    void utW06_结果超上限_RESULT_OVERSIZE() {
        ToolGateway gateway = gateway(
                new ToolRegistry.Registration(definition("t.tool", ToolRisk.R0, 1_000, 16),
                        execution -> new byte[64]),
                new ToolPolicy(Set.of("t.tool")), POOL);
        assertThatThrownBy(() -> gateway.invoke(invocation("t.tool", Map.of("q", "x"))))
                .isInstanceOfSatisfying(ToolControlPlaneException.class,
                        e -> assertThat(e.reason()).isEqualTo(ToolControlReason.RESULT_OVERSIZE));
    }

    @Test
    void utW07_硬deadline超时_TIMEOUT_RETRYABLE且任务被中断() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            ToolGateway gateway = gateway(
                    new ToolRegistry.Registration(
                            definition("slow.tool", ToolRisk.R0, 200, 16),
                            execution -> {
                                try {
                                    release.await();
                                } catch (InterruptedException e) {
                                    interrupted.countDown();
                                    Thread.currentThread().interrupt();
                                }
                                return new byte[1];
                            }),
                    new ToolPolicy(Set.of("slow.tool")), pool);
            assertThatThrownBy(() -> gateway.invoke(invocation("slow.tool", Map.of("q", "x"))))
                    .isInstanceOfSatisfying(ToolModelVisibleException.class,
                            e -> assertThat(e.reason())
                                    .isEqualTo(ToolModelVisibleReason.TIMEOUT_RETRYABLE));
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue(); // 迟到结果作废
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void utW08_executor意外异常_映射脱敏固定文案_不透传内部信息() {
        ToolGateway gateway = gateway(
                new ToolRegistry.Registration(definition("t.tool", ToolRisk.R0, 1_000, 16),
                        execution -> {
                            throw new IllegalStateException(
                                    "connect refused to http://10.0.0.7:9090 secret=abc");
                        }),
                new ToolPolicy(Set.of("t.tool")), POOL);
        ToolModelVisibleException e = catchThrowableOfType(
                () -> gateway.invoke(invocation("t.tool", Map.of("q", "x"))),
                ToolModelVisibleException.class);
        assertThat(e.reason()).isEqualTo(ToolModelVisibleReason.REMOTE_UNAVAILABLE);
        assertThat(e).hasMessage("工具远端暂不可用（临时故障，可重试）");
        assertThat(String.valueOf(e)).doesNotContain("10.0.0.7").doesNotContain("secret");
    }

    @Test
    void utW09_executor自判模型可见族_NO_DATA原样保留() {
        ToolGateway gateway = gateway(
                new ToolRegistry.Registration(definition("t.tool", ToolRisk.R0, 1_000, 16),
                        execution -> {
                            throw new ToolModelVisibleException(
                                    ToolModelVisibleReason.NO_DATA, "无数据");
                        }),
                new ToolPolicy(Set.of("t.tool")), POOL);
        assertThatThrownBy(() -> gateway.invoke(invocation("t.tool", Map.of("q", "x"))))
                .isInstanceOfSatisfying(ToolModelVisibleException.class,
                        e -> assertThat(e.reason()).isEqualTo(ToolModelVisibleReason.NO_DATA));
    }

    @Test
    void utW10_executor显式终止族_AUTH_FAILED穿透() {
        ToolGateway gateway = gateway(
                new ToolRegistry.Registration(definition("t.tool", ToolRisk.R0, 1_000, 16),
                        execution -> {
                            throw new ToolControlPlaneException(
                                    ToolControlReason.AUTH_FAILED, "AUTH_FAILED");
                        }),
                new ToolPolicy(Set.of("t.tool")), POOL);
        assertThatThrownBy(() -> gateway.invoke(invocation("t.tool", Map.of("q", "x"))))
                .isInstanceOfSatisfying(ToolControlPlaneException.class,
                        e -> assertThat(e.reason()).isEqualTo(ToolControlReason.AUTH_FAILED));
    }

    @Test
    void utW11_bulkhead满则明确拒绝_模型可见背压文案() throws Exception {
        // EX-A4a（F17）：单槽池被占 + 有界队列(1)满 → 第三个 submit 即
        // RejectedExecutionException，映射为模型可见背压固定文案（不静默排队）
        ExecutorService busyPool = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
                TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(1),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        CountDownLatch release = new CountDownLatch(1);
        try {
            busyPool.submit(() -> release.await(5, TimeUnit.SECONDS));
            busyPool.submit(() -> release.await(5, TimeUnit.SECONDS)); // 队列满
            ToolGateway gateway = gateway(
                    new ToolRegistry.Registration(definition("t.tool", ToolRisk.R0, 1_000, 16),
                            countingExecutor()),
                    new ToolPolicy(Set.of("t.tool")), busyPool);
            ToolModelVisibleException e = catchThrowableOfType(
                    () -> gateway.invoke(invocation("t.tool", Map.of("q", "x"))),
                    ToolModelVisibleException.class);
            assertThat(e.reason()).isEqualTo(ToolModelVisibleReason.REMOTE_UNAVAILABLE);
            assertThat(e).hasMessage("工具调用通道拥塞（背压拒绝，可稍后重试）");
            assertThat(remoteCalls.get()).isZero();
        } finally {
            release.countDown();
            busyPool.shutdownNow();
        }
    }

    // ------------------------------------------------- WC-3 取消传播（§5.1/§5.2）

    @Test
    void wc3_t13a_外部硬期限已过_提交前即停_零执行() {
        ToolGateway gateway = gateway(
                new ToolRegistry.Registration(definition("t.tool", ToolRisk.R0, 10_000, 16),
                        countingExecutor()),
                new ToolPolicy(Set.of("t.tool")), POOL);
        ToolGateway.ToolInvocation inv = new ToolGateway.ToolInvocation(RUN, RUN, RUN, 1,
                "t.tool", "1.0.0", "r", Map.of("q", "x"), null,
                FIXED.instant().minusMillis(1));
        com.objwww.pr.control.alert.application.ExecutionControl.StoppedException e =
                catchThrowableOfType(() -> gateway.invoke(inv),
                        com.objwww.pr.control.alert.application.ExecutionControl
                                .StoppedException.class);
        assertThat(e.kind()).isEqualTo(
                com.objwww.pr.control.alert.application.ExecutionControl
                        .STOP_RUN_DEADLINE_EXCEEDED);
        assertThat(remoteCalls.get()).as("停止在提交前：零执行").isZero();
    }

    @Test
    void wc3_t13b_外部期限收紧等待_executor收到夹紧deadline_超时面不变() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Long> seenDeadline = new AtomicReference<>();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 工具超时 10s，外部期限 = now+150ms → 等待必须在夹紧点结束（不等 10s）
            ToolGateway gateway = gateway(
                    new ToolRegistry.Registration(
                            definition("slow.tool", ToolRisk.R0, 10_000, 16),
                            execution -> {
                                seenDeadline.set(execution.deadlineEpochMillis());
                                release.await();
                                return new byte[1];
                            }),
                    new ToolPolicy(Set.of("slow.tool")), pool);
            ToolGateway.ToolInvocation inv = new ToolGateway.ToolInvocation(RUN, RUN, RUN, 1,
                    "slow.tool", "1.0.0", "r", Map.of("q", "x"), null,
                    FIXED.instant().plusMillis(150));
            long begin = System.nanoTime();
            ToolModelVisibleException e = catchThrowableOfType(() -> gateway.invoke(inv),
                    ToolModelVisibleException.class);
            long elapsedMs = (System.nanoTime() - begin) / 1_000_000;
            assertThat(e.reason()).isEqualTo(ToolModelVisibleReason.TIMEOUT_RETRYABLE);
            assertThat(elapsedMs).as("等待被外部期限夹紧（远小于 10s 工具超时）")
                    .isLessThan(5_000);
            assertThat(seenDeadline.get()).as("executor 收到夹紧后的 deadline")
                    .isEqualTo(FIXED.instant().plusMillis(150).toEpochMilli());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void wc3_t13c_Run取消的在途通知_中断工具等待_转RUN_CANCELLED停止() throws Exception {
        InFlightToolCancels cancels = new InFlightToolCancels();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        try {
            ToolGateway gateway = new ToolGateway(
                    new ToolRegistry(List.of(new ToolRegistry.Registration(
                            definition("slow.tool", ToolRisk.R0, 60_000, 16),
                            execution -> {
                                started.countDown();
                                release.await();
                                return new byte[1];
                            }))),
                    new ToolPolicy(Set.of("slow.tool")), pool, FIXED, null, cancels);
            Thread caller = new Thread(() -> {
                try {
                    gateway.invoke(new ToolGateway.ToolInvocation(RUN, RUN, RUN, 1,
                            "slow.tool", "1.0.0", "r", Map.of("q", "x"), null, null));
                } catch (Throwable t) {
                    thrown.set(t);
                }
            });
            caller.start();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            cancels.cancelRun(RUN); // CANCEL 应用后的后置通知

            caller.join(5_000);
            assertThat(thrown.get()).isNotNull();
            assertThat(thrown.get()).isInstanceOfSatisfying(
                    com.objwww.pr.control.alert.application.ExecutionControl
                            .StoppedException.class,
                    e -> assertThat(((com.objwww.pr.control.alert.application.ExecutionControl
                            .StoppedException) e).kind())
                            .isEqualTo(com.objwww.pr.control.alert.application.ExecutionControl
                                    .STOP_RUN_CANCELLED));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
