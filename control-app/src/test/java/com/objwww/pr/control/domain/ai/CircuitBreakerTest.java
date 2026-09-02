package com.objwww.pr.control.domain.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CircuitBreaker 三态迁移（UT-61 注入单调时钟）：CLOSED 精确阈值 OPEN、onSuccess 清零、
 * 中性归还不累加不清零、冷却期恰好一发探针、并发抢探针唯一、探针悬挂防护、OPEN 原因域保留。
 */
class CircuitBreakerTest {

    private static final long COOLDOWN = 1_000L; // nanos

    private final AtomicLong nanos = new AtomicLong(0);

    private CircuitBreaker breaker(int threshold) {
        return new CircuitBreaker(threshold, COOLDOWN, nanos::get);
    }

    @Test
    void constructorRejectsNonPositiveArgs() {
        assertThatThrownBy(() -> new CircuitBreaker(0, COOLDOWN, nanos::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreaker(1, 0, nanos::get))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closedDoesNotOpenBelowThresholdOpensExactlyAtThreshold() {
        CircuitBreaker b = breaker(3);
        try (BreakerPermit p1 = b.tryAcquire()) { p1.onFailure(FaultScope.ENDPOINT); }
        try (BreakerPermit p2 = b.tryAcquire()) { p2.onFailure(FaultScope.ENDPOINT); }
        // 第 N-1=2 次失败：仍 CLOSED
        assertThat(b.currentState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(b.consecutiveFailures()).isEqualTo(2);

        try (BreakerPermit p3 = b.tryAcquire()) { p3.onFailure(FaultScope.ENDPOINT); }
        // 第 N=3 次：精确 OPEN
        assertThat(b.currentState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void successResetsConsecutiveFailureCount() {
        CircuitBreaker b = breaker(3);
        try (BreakerPermit p = b.tryAcquire()) { p.onFailure(FaultScope.ENDPOINT); }
        try (BreakerPermit p = b.tryAcquire()) { p.onFailure(FaultScope.ENDPOINT); }
        try (BreakerPermit p = b.tryAcquire()) { p.onSuccess(); }
        assertThat(b.consecutiveFailures()).isZero();

        // 重计：再失败 2 次仍 CLOSED，第 3 次才 OPEN
        try (BreakerPermit p = b.tryAcquire()) { p.onFailure(FaultScope.ENDPOINT); }
        try (BreakerPermit p = b.tryAcquire()) { p.onFailure(FaultScope.ENDPOINT); }
        assertThat(b.currentState()).isEqualTo(CircuitBreaker.State.CLOSED);
        try (BreakerPermit p = b.tryAcquire()) { p.onFailure(FaultScope.ENDPOINT); }
        assertThat(b.currentState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void neutralPermitCloseNeitherAccumulatesNorClears() {
        CircuitBreaker b = breaker(3);
        try (BreakerPermit p = b.tryAcquire()) { p.onFailure(FaultScope.ENDPOINT); }
        assertThat(b.consecutiveFailures()).isEqualTo(1);

        // 中性归还（不调 onSuccess/onFailure）：计数不变、状态不变
        try (BreakerPermit ignored = b.tryAcquire()) { /* close 中性归还 */ }
        assertThat(b.consecutiveFailures()).isEqualTo(1);
        assertThat(b.currentState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // 未清零的证据：再 2 次失败即达阈值 OPEN
        try (BreakerPermit p = b.tryAcquire()) { p.onFailure(FaultScope.ENDPOINT); }
        try (BreakerPermit p = b.tryAcquire()) { p.onFailure(FaultScope.ENDPOINT); }
        assertThat(b.currentState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void openRejectsBeforeCooldownAndGrantsExactlyOneProbeAfter() {
        CircuitBreaker b = openBreaker(3);
        // 冷却期未到：快败 null
        nanos.set(COOLDOWN - 1);
        assertThat(b.tryAcquire()).isNull();
        assertThat(b.currentState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 冷却期到点：恰好一发 permit，转 HALF_OPEN
        nanos.set(COOLDOWN);
        BreakerPermit probe = b.tryAcquire();
        assertThat(probe).isNotNull();
        assertThat(b.currentState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        // 第二发被拒（探针唯一）
        assertThat(b.tryAcquire()).isNull();
        probe.onSuccess();
    }

    @Test
    void concurrentHalfOpenAcquireGrantsExactlyOnePermit() throws Exception {
        CircuitBreaker b = openBreaker(3);
        nanos.set(COOLDOWN); // 冷却到期

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<BreakerPermit>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return b.tryAcquire();
            }));
        }
        gate.countDown();
        int granted = 0;
        for (Future<BreakerPermit> f : futures) {
            if (f.get() != null) {
                granted++;
            }
        }
        pool.shutdown();
        assertThat(granted).isEqualTo(1);
        assertThat(b.currentState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void probeClosedWithoutCallbackReopensAndRecoversAfterNewCooldown() {
        CircuitBreaker b = openBreaker(3);
        nanos.set(COOLDOWN);
        BreakerPermit probe = b.tryAcquire();
        assertThat(probe).isNotNull();

        // 探针未回调即 close（中断/取消/无结论）：转 OPEN 重计时，不永久卡 HALF_OPEN
        probe.close();
        assertThat(b.currentState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 重计时期间快败
        assertThat(b.tryAcquire()).isNull();
        // 新冷却期到点后可再次拿探针——状态机未卡死
        nanos.set(COOLDOWN * 2);
        BreakerPermit probe2 = b.tryAcquire();
        assertThat(probe2).isNotNull();
        probe2.onSuccess();
        assertThat(b.currentState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void probeSuccessClosesBreakerAndClearsScope() {
        CircuitBreaker b = openBreaker(3);
        nanos.set(COOLDOWN);
        BreakerPermit probe = b.tryAcquire();
        probe.onSuccess();
        assertThat(b.currentState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(b.consecutiveFailures()).isZero();
        assertThat(b.openedScope()).isNull();
    }

    @Test
    void probeFailureReopensWithRetimedCooldownAndNewScope() {
        CircuitBreaker b = openBreaker(3);
        nanos.set(COOLDOWN);
        BreakerPermit probe = b.tryAcquire();
        probe.onFailure(FaultScope.ACCOUNT);
        assertThat(b.currentState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(b.openedScope()).isEqualTo(FaultScope.ACCOUNT);
        // 从探针失败时刻重计时：旧冷却点不放行
        nanos.set(COOLDOWN * 2 - 1);
        assertThat(b.tryAcquire()).isNull();
        nanos.set(COOLDOWN * 2);
        assertThat(b.tryAcquire()).isNotNull();
    }

    @Test
    void openedScopeRetainsOpenReasonOnlyWhileOpen() {
        CircuitBreaker b = breaker(2);
        assertThat(b.openedScope()).isNull(); // CLOSED → null
        try (BreakerPermit p = b.tryAcquire()) { p.onFailure(FaultScope.ENDPOINT); }
        try (BreakerPermit p = b.tryAcquire()) { p.onFailure(FaultScope.ENDPOINT); }
        assertThat(b.currentState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(b.openedScope()).isEqualTo(FaultScope.ENDPOINT);
    }

    /** 灌 failureThreshold 次计数型失败使熔断器 OPEN（openedAt=nanos 0） */
    private CircuitBreaker openBreaker(int threshold) {
        CircuitBreaker b = breaker(threshold);
        for (int i = 0; i < threshold; i++) {
            try (BreakerPermit p = b.tryAcquire()) { p.onFailure(FaultScope.ENDPOINT); }
        }
        assertThat(b.currentState()).isEqualTo(CircuitBreaker.State.OPEN);
        return b;
    }
}
