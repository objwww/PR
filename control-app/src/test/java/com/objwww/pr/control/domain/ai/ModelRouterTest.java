package com.objwww.pr.control.domain.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ModelRouter 决策表全类族穷举（UT-30）：终态族/可重试族 × 重试余量 × fallback 资格 × G1/G2/G3 闸门。
 * jitter 注入定值 1.0，退避值可精确断言。
 */
class ModelRouterTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant NOW = T0.plusSeconds(1);

    private static final ModelRoute PRIMARY = route("p", "ep-a", "q-a", "c-a");
    private static final ModelRoute FB_DIFF_ALL = route("f", "ep-b", "q-b", "c-b");
    private static final ModelRoute FB_SAME_EP = route("f2", "ep-a", "q-b", "c-b");
    private static final ModelRoute FB_SAME_Q = route("f3", "ep-b", "q-a", "c-b");
    private static final ModelRoute FB_SAME_CRED = route("f4", "ep-b", "q-b", "c-a");
    private static final ModelRoute FB_IDENTICAL = route("f5", "ep-a", "q-a", "c-a");

    private static ModelRoute route(String id, String ep, String quota, String cred) {
        return new ModelRoute(id, "m-" + id, ep, quota, cred, null);
    }

    /** maxCallRetries=2（单路由物理上限 3），backoffBase=1s，backoffMax=60s，jitter=1.0 */
    private static ModelRouter router(ModelRoute fallback) {
        return new ModelRouter(PRIMARY, fallback, 2,
                Duration.ofSeconds(1), Duration.ofSeconds(60), () -> 1.0);
    }

    private static ModelStepBudgetGuard budget(int maxCalls, Duration deadline, Duration inlineMax) {
        return new ModelStepBudgetGuard(maxCalls, 100_000, 100_000, 1_000_000,
                deadline, inlineMax, T0);
    }

    /** 宽余量预算：remaining=299s，inlineMax=120s */
    private static ModelStepBudgetGuard generousBudget() {
        return budget(10, Duration.ofSeconds(300), Duration.ofSeconds(120));
    }

    // ------------------------------------------------------------------ 终态族（A1/A2/A3/A4/A13/A14）

    @Test
    void requestInvalidAlwaysFailsEvenWhenModelScopeFallbackEligible() {
        ModelRouter r = router(FB_DIFF_ALL);
        RouteDecision d = r.decide(new ModelCallFailure.RequestInvalid(FaultScope.MODEL),
                PRIMARY, 1, false, generousBudget(), NOW);
        assertThat(d).isEqualTo(new RouteDecision.Fail("REQUEST_INVALID", false));
    }

    @Test
    void authDeniedFallsBackOnlyOnDifferentCredentialDomain() {
        // 异 credentialDomain → Fallback
        RouteDecision d = router(FB_DIFF_ALL).decide(new ModelCallFailure.AuthDenied(FaultScope.CREDENTIAL),
                PRIMARY, 1, false, generousBudget(), NOW);
        assertThat(d).isEqualTo(new RouteDecision.Fallback(FB_DIFF_ALL));
        // 同 credentialDomain → 终态
        assertThat(router(FB_SAME_CRED).decide(new ModelCallFailure.AuthDenied(FaultScope.CREDENTIAL),
                PRIMARY, 1, false, generousBudget(), NOW))
                .isEqualTo(new RouteDecision.Fail("AUTH_DENIED", false));
        // 已 fallback 过 → 终态
        assertThat(router(FB_DIFF_ALL).decide(new ModelCallFailure.AuthDenied(FaultScope.CREDENTIAL),
                FB_DIFF_ALL, 1, true, generousBudget(), NOW))
                .isEqualTo(new RouteDecision.Fail("AUTH_DENIED", false));
        // 无 fallback 路由 → 终态
        assertThat(router(null).decide(new ModelCallFailure.AuthDenied(FaultScope.CREDENTIAL),
                PRIMARY, 1, false, generousBudget(), NOW))
                .isEqualTo(new RouteDecision.Fail("AUTH_DENIED", false));
    }

    @Test
    void billingOrActivationAlwaysFails() {
        assertThat(router(FB_DIFF_ALL).decide(new ModelCallFailure.BillingOrActivation(FaultScope.ACCOUNT),
                PRIMARY, 1, false, generousBudget(), NOW))
                .isEqualTo(new RouteDecision.Fail("BILLING_OR_ACTIVATION", false));
    }

    @Test
    void quotaExhaustedFallsBackOnlyOnDifferentQuotaScope() {
        assertThat(router(FB_DIFF_ALL).decide(new ModelCallFailure.QuotaExhausted(FaultScope.ACCOUNT),
                PRIMARY, 1, false, generousBudget(), NOW))
                .isEqualTo(new RouteDecision.Fallback(FB_DIFF_ALL));
        assertThat(router(FB_SAME_Q).decide(new ModelCallFailure.QuotaExhausted(FaultScope.ACCOUNT),
                PRIMARY, 1, false, generousBudget(), NOW))
                .isEqualTo(new RouteDecision.Fail("QUOTA_EXHAUSTED", false));
        assertThat(router(FB_DIFF_ALL).decide(new ModelCallFailure.QuotaExhausted(FaultScope.ACCOUNT),
                FB_DIFF_ALL, 1, true, generousBudget(), NOW))
                .isEqualTo(new RouteDecision.Fail("QUOTA_EXHAUSTED", false));
    }

    @Test
    void protocolErrorAlwaysFails() {
        assertThat(router(FB_DIFF_ALL).decide(new ModelCallFailure.ProtocolError(FaultScope.ENDPOINT),
                PRIMARY, 1, false, generousBudget(), NOW))
                .isEqualTo(new RouteDecision.Fail("PROTOCOL_ERROR", false));
    }

    @Test
    void unknownErrorFailsWithReason() {
        assertThat(router(FB_DIFF_ALL).decide(new ModelCallFailure.UnknownError("boom"),
                PRIMARY, 1, false, generousBudget(), NOW))
                .isEqualTo(new RouteDecision.Fail("UNKNOWN_ERROR:boom", false));
    }

    // ------------------------------------------------------------------ 可重试族（A5~A12）

    @Test
    void remoteTimeoutRetriesSameRouteWithinRetries() {
        RouteDecision d = router(FB_DIFF_ALL).decide(new ModelCallFailure.Timeout(FaultScope.ENDPOINT, true),
                PRIMARY, 1, false, generousBudget(), NOW);
        assertThat(d).isEqualTo(new RouteDecision.RetrySameRoute(Duration.ofMillis(1000)));
    }

    @Test
    void remoteTimeoutExhaustedRetriesFallsBackOnDifferentEndpoint() {
        // callsOnCurrentRoute=3 = maxCallRetries+1 → 同路由上限耗尽
        RouteDecision d = router(FB_DIFF_ALL).decide(new ModelCallFailure.Timeout(FaultScope.ENDPOINT, true),
                PRIMARY, 3, false, generousBudget(), NOW);
        assertThat(d).isEqualTo(new RouteDecision.Fallback(FB_DIFF_ALL));
    }

    @Test
    void remoteTimeoutExhaustedSameEndpointDefers() {
        RouteDecision d = router(FB_SAME_EP).decide(new ModelCallFailure.Timeout(FaultScope.ENDPOINT, true),
                PRIMARY, 3, false, generousBudget(), NOW);
        // desiredWait = base×2^(3-1) = 4s，Defer.notBefore = now + 4s
        assertThat(d).isEqualTo(new RouteDecision.Defer(NOW.plusSeconds(4)));
    }

    @Test
    void remoteTimeoutExhaustedNoFallbackRouteDefers() {
        RouteDecision d = router(null).decide(new ModelCallFailure.Timeout(FaultScope.ENDPOINT, true),
                PRIMARY, 3, false, generousBudget(), NOW);
        assertThat(d).isEqualTo(new RouteDecision.Defer(NOW.plusSeconds(4)));
    }

    @Test
    void localTimeoutNeverRetriesSameRoute() {
        // A11：remote=false 即使重试余量充足也不原地重试
        RouteDecision fb = router(FB_DIFF_ALL).decide(new ModelCallFailure.Timeout(FaultScope.ENDPOINT, false),
                PRIMARY, 1, false, generousBudget(), NOW);
        assertThat(fb).isEqualTo(new RouteDecision.Fallback(FB_DIFF_ALL));
        // 无 fallback 资格 → Defer，backoff = base×2^0 = 1s
        RouteDecision defer = router(FB_SAME_EP).decide(new ModelCallFailure.Timeout(FaultScope.ENDPOINT, false),
                PRIMARY, 1, false, generousBudget(), NOW);
        assertThat(defer).isEqualTo(new RouteDecision.Defer(NOW.plusSeconds(1)));
    }

    @Test
    void networkErrorRetriesWithFlatBaseBackoff() {
        // A12：NetworkError 恒为 backoffBase 短退避，与调用次数无关
        RouteDecision d = router(FB_DIFF_ALL).decide(new ModelCallFailure.NetworkError(FaultScope.ENDPOINT),
                PRIMARY, 2, false, generousBudget(), NOW);
        assertThat(d).isEqualTo(new RouteDecision.RetrySameRoute(Duration.ofSeconds(1)));
    }

    @Test
    void networkErrorExhaustedFallsBackOrDefers() {
        assertThat(router(FB_DIFF_ALL).decide(new ModelCallFailure.NetworkError(FaultScope.ENDPOINT),
                PRIMARY, 3, false, generousBudget(), NOW))
                .isEqualTo(new RouteDecision.Fallback(FB_DIFF_ALL));
        assertThat(router(FB_SAME_EP).decide(new ModelCallFailure.NetworkError(FaultScope.ENDPOINT),
                PRIMARY, 3, false, generousBudget(), NOW))
                .isEqualTo(new RouteDecision.Defer(NOW.plusSeconds(1)));
    }

    @Test
    void rateLimitedWithRetryAfterRetriesThatExactWait() {
        RouteDecision d = router(FB_DIFF_ALL).decide(
                new ModelCallFailure.RateLimitedTransient(FaultScope.MODEL, Duration.ofSeconds(5)),
                PRIMARY, 1, false, generousBudget(), NOW);
        assertThat(d).isEqualTo(new RouteDecision.RetrySameRoute(Duration.ofSeconds(5)));
    }

    @Test
    void rateLimitedModelScopeFallsBackEvenOnIdenticalDomains() {
        // MODEL 域：同域也允许 fallback（C-2 唯一放行的同域族）
        RouteDecision d = router(FB_IDENTICAL).decide(
                new ModelCallFailure.RateLimitedTransient(FaultScope.MODEL, Duration.ofSeconds(5)),
                PRIMARY, 3, false, generousBudget(), NOW);
        assertThat(d).isEqualTo(new RouteDecision.Fallback(FB_IDENTICAL));
    }

    @Test
    void rateLimitedModelScopeAlreadyFallbackedDefers() {
        RouteDecision d = router(FB_IDENTICAL).decide(
                new ModelCallFailure.RateLimitedTransient(FaultScope.MODEL, Duration.ofSeconds(5)),
                FB_IDENTICAL, 3, true, generousBudget(), NOW);
        assertThat(d).isEqualTo(new RouteDecision.Defer(NOW.plusSeconds(5)));
    }

    @Test
    void rateLimitedAccountScopeRequiresDifferentQuotaScope() {
        ModelCallFailure f = new ModelCallFailure.RateLimitedTransient(FaultScope.ACCOUNT, Duration.ofSeconds(5));
        assertThat(router(FB_DIFF_ALL).decide(f, PRIMARY, 3, false, generousBudget(), NOW))
                .isEqualTo(new RouteDecision.Fallback(FB_DIFF_ALL));
        assertThat(router(FB_SAME_Q).decide(f, PRIMARY, 3, false, generousBudget(), NOW))
                .isEqualTo(new RouteDecision.Defer(NOW.plusSeconds(5)));
    }

    @Test
    void quotaTemporaryRetriesWhenWaitFits() {
        Instant notBefore = NOW.plusSeconds(30);
        RouteDecision d = router(FB_DIFF_ALL).decide(
                new ModelCallFailure.QuotaTemporary(FaultScope.ACCOUNT, notBefore),
                PRIMARY, 1, false, generousBudget(), NOW);
        assertThat(d).isEqualTo(new RouteDecision.RetrySameRoute(Duration.ofSeconds(30)));
    }

    @Test
    void quotaTemporaryDeferCarriesExactNotBefore() {
        Instant notBefore = NOW.plusSeconds(30);
        // 耗尽重试 + 同 quotaScope → Defer，notBefore 必须用故障携带值而非 now+wait 重算
        RouteDecision d = router(FB_SAME_Q).decide(
                new ModelCallFailure.QuotaTemporary(FaultScope.ACCOUNT, notBefore),
                PRIMARY, 3, false, generousBudget(), NOW);
        assertThat(d).isEqualTo(new RouteDecision.Defer(notBefore));
    }

    @Test
    void quotaTemporaryPastNotBeforeWaitsZero() {
        RouteDecision d = router(FB_DIFF_ALL).decide(
                new ModelCallFailure.QuotaTemporary(FaultScope.ACCOUNT, NOW.minusSeconds(5)),
                PRIMARY, 1, false, generousBudget(), NOW);
        assertThat(d).isEqualTo(new RouteDecision.RetrySameRoute(Duration.ZERO));
    }

    @Test
    void serverErrorRetriesThenFallsBackThenDefers() {
        ModelCallFailure f = new ModelCallFailure.ServerError(FaultScope.ENDPOINT);
        assertThat(router(FB_DIFF_ALL).decide(f, PRIMARY, 1, false, generousBudget(), NOW))
                .isEqualTo(new RouteDecision.RetrySameRoute(Duration.ofSeconds(1)));
        assertThat(router(FB_DIFF_ALL).decide(f, PRIMARY, 3, false, generousBudget(), NOW))
                .isEqualTo(new RouteDecision.Fallback(FB_DIFF_ALL));
        // 已 fallback 且耗尽 → Defer（calls=3 → 4s）
        assertThat(router(FB_DIFF_ALL).decide(f, FB_DIFF_ALL, 3, true, generousBudget(), NOW))
                .isEqualTo(new RouteDecision.Defer(NOW.plusSeconds(4)));
    }

    // ------------------------------------------------------------------ 指数退避 + 抖动 + 封顶

    @Test
    void exponentialBackoffDoublesWithJitterOne() {
        ModelRouter r = new ModelRouter(PRIMARY, null, 10,
                Duration.ofSeconds(1), Duration.ofSeconds(600), () -> 1.0);
        ModelCallFailure f = new ModelCallFailure.ServerError(FaultScope.ENDPOINT);
        ModelStepBudgetGuard b = budget(20, Duration.ofSeconds(3600), Duration.ofSeconds(600));
        assertThat(r.decide(f, PRIMARY, 1, false, b, NOW))
                .isEqualTo(new RouteDecision.RetrySameRoute(Duration.ofMillis(1000)));
        assertThat(r.decide(f, PRIMARY, 2, false, b, NOW))
                .isEqualTo(new RouteDecision.RetrySameRoute(Duration.ofMillis(2000)));
        assertThat(r.decide(f, PRIMARY, 3, false, b, NOW))
                .isEqualTo(new RouteDecision.RetrySameRoute(Duration.ofMillis(4000)));
        assertThat(r.decide(f, PRIMARY, 4, false, b, NOW))
                .isEqualTo(new RouteDecision.RetrySameRoute(Duration.ofMillis(8000)));
    }

    @Test
    void exponentialBackoffCappedAtBackoffMax() {
        ModelRouter r = new ModelRouter(PRIMARY, null, 20,
                Duration.ofSeconds(1), Duration.ofSeconds(60), () -> 1.0);
        ModelCallFailure f = new ModelCallFailure.ServerError(FaultScope.ENDPOINT);
        ModelStepBudgetGuard b = budget(30, Duration.ofSeconds(3600), Duration.ofSeconds(600));
        // calls=8 → shift=6 → 1s×64=64s → 封顶 60s
        assertThat(r.decide(f, PRIMARY, 8, false, b, NOW))
                .isEqualTo(new RouteDecision.RetrySameRoute(Duration.ofSeconds(60)));
    }

    @Test
    void jitterFactorScalesBackoff() {
        ModelRouter r = new ModelRouter(PRIMARY, null, 10,
                Duration.ofSeconds(1), Duration.ofSeconds(60), () -> 0.5);
        ModelCallFailure f = new ModelCallFailure.ServerError(FaultScope.ENDPOINT);
        assertThat(r.decide(f, PRIMARY, 2, false, generousBudget(), NOW))
                .isEqualTo(new RouteDecision.RetrySameRoute(Duration.ofMillis(1000)));
    }

    // ------------------------------------------------------------------ 通用闸门 G1/G2/G3

    @Test
    void g1PhysicalCallBudgetExhaustedFailsRegardlessOfFailureType() {
        ModelStepBudgetGuard b = budget(1, Duration.ofSeconds(300), Duration.ofSeconds(120));
        b.recordCallStarted(); // 预算耗尽
        RouteDecision d = router(FB_DIFF_ALL).decide(new ModelCallFailure.NetworkError(FaultScope.ENDPOINT),
                PRIMARY, 1, false, b, NOW);
        assertThat(d).isEqualTo(new RouteDecision.Fail("BUDGET_EXHAUSTED", false));
    }

    @Test
    void g2DeadlineExhaustedFailsStepRetryable() {
        ModelStepBudgetGuard b = budget(10, Duration.ofSeconds(1), Duration.ofSeconds(120));
        RouteDecision d = router(FB_DIFF_ALL).decide(new ModelCallFailure.NetworkError(FaultScope.ENDPOINT),
                PRIMARY, 1, false, b, T0.plusSeconds(1)); // remaining = 0
        assertThat(d).isEqualTo(new RouteDecision.Fail("DEADLINE_EXCEEDED", true));
    }

    @Test
    void g3WaitExceedingInlineMaxSkipsSameRouteRetry() {
        // ServerError 等 1s > inlineMax 500ms → 不原地重试；同 endpoint → Defer
        ModelStepBudgetGuard b = budget(10, Duration.ofSeconds(300), Duration.ofMillis(500));
        RouteDecision d = router(FB_SAME_EP).decide(new ModelCallFailure.ServerError(FaultScope.ENDPOINT),
                PRIMARY, 1, false, b, NOW);
        assertThat(d).isEqualTo(new RouteDecision.Defer(NOW.plusSeconds(1)));
    }

    @Test
    void waitExceedingRemainingDeadlineSkipsSameRouteRetry() {
        // remaining=1s，retryAfter=5s → 不原地重试；ACCOUNT 域同 quotaScope → Defer(now+5s)
        ModelStepBudgetGuard b = budget(10, Duration.ofSeconds(3), Duration.ofSeconds(120));
        RouteDecision d = router(FB_SAME_Q).decide(
                new ModelCallFailure.RateLimitedTransient(FaultScope.ACCOUNT, Duration.ofSeconds(5)),
                PRIMARY, 1, false, b, T0.plusSeconds(2));
        assertThat(d).isEqualTo(new RouteDecision.Defer(T0.plusSeconds(2).plusSeconds(5)));
    }

    // ------------------------------------------------------------------ canFallback 四域矩阵

    @Test
    void canFallbackMatrix() {
        ModelRouter diffAll = router(FB_DIFF_ALL);
        // MODEL 域：同域也允许（域全同仍放行）
        assertThat(router(FB_IDENTICAL).canFallback(FaultScope.MODEL, PRIMARY)).isTrue();
        // ENDPOINT：异 endpointScope 才允许
        assertThat(diffAll.canFallback(FaultScope.ENDPOINT, PRIMARY)).isTrue();
        assertThat(router(FB_SAME_EP).canFallback(FaultScope.ENDPOINT, PRIMARY)).isFalse();
        // ACCOUNT：异 quotaScope 才允许
        assertThat(diffAll.canFallback(FaultScope.ACCOUNT, PRIMARY)).isTrue();
        assertThat(router(FB_SAME_Q).canFallback(FaultScope.ACCOUNT, PRIMARY)).isFalse();
        // CREDENTIAL：异 credentialDomain 才允许
        assertThat(diffAll.canFallback(FaultScope.CREDENTIAL, PRIMARY)).isTrue();
        assertThat(router(FB_SAME_CRED).canFallback(FaultScope.CREDENTIAL, PRIMARY)).isFalse();
        // 域不确定 → 禁止
        assertThat(diffAll.canFallback(null, PRIMARY)).isFalse();
        // 无 fallback 路由 → 禁止
        assertThat(router(null).canFallback(FaultScope.MODEL, PRIMARY)).isFalse();
    }

    // ------------------------------------------------------------------ countsForBreaker

    @Test
    void countsForBreakerCountsExactlySixPostNetworkFamilies() {
        assertThat(ModelRouter.countsForBreaker(new ModelCallFailure.Timeout(FaultScope.ENDPOINT, true))).isTrue();
        assertThat(ModelRouter.countsForBreaker(new ModelCallFailure.Timeout(FaultScope.ENDPOINT, false))).isTrue();
        assertThat(ModelRouter.countsForBreaker(new ModelCallFailure.NetworkError(FaultScope.ENDPOINT))).isTrue();
        assertThat(ModelRouter.countsForBreaker(new ModelCallFailure.ProtocolError(FaultScope.ENDPOINT))).isTrue();
        assertThat(ModelRouter.countsForBreaker(
                new ModelCallFailure.RateLimitedTransient(FaultScope.MODEL, null))).isTrue();
        assertThat(ModelRouter.countsForBreaker(
                new ModelCallFailure.QuotaTemporary(FaultScope.ACCOUNT, NOW))).isTrue();
        assertThat(ModelRouter.countsForBreaker(new ModelCallFailure.ServerError(FaultScope.ENDPOINT))).isTrue();
    }

    @Test
    void countsForBreakerExcludesClientErrorFamilies() {
        assertThat(ModelRouter.countsForBreaker(new ModelCallFailure.RequestInvalid(FaultScope.MODEL))).isFalse();
        assertThat(ModelRouter.countsForBreaker(new ModelCallFailure.AuthDenied(FaultScope.CREDENTIAL))).isFalse();
        assertThat(ModelRouter.countsForBreaker(new ModelCallFailure.BillingOrActivation(FaultScope.ACCOUNT)))
                .isFalse();
        assertThat(ModelRouter.countsForBreaker(new ModelCallFailure.QuotaExhausted(FaultScope.ACCOUNT)))
                .isFalse();
        assertThat(ModelRouter.countsForBreaker(new ModelCallFailure.UnknownError("x"))).isFalse();
    }
}
