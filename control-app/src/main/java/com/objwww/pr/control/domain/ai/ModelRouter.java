package com.objwww.pr.control.domain.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 纯决策（§4.2/附录 A）：故障分类 + FaultScope + 熔断状态 + 剩余预算 → RouteDecision。
 *
 * <p>决策表 A1~A16 + 通用闸门 G1~G5 的唯一实现处；无副作用、不发起调用、不写账、不持状态。
 * UT-30 按 A1~A16 × G1~G5 全组合穷举本类。
 */
public final class ModelRouter {

    private final ModelRoute primaryRoute;
    private final ModelRoute fallbackRoute; // 可为 null（单路由）
    private final int maxCallRetries;
    private final Duration backoffBase;
    private final Duration backoffMax;
    private final DoubleSupplier jitter; // 抖动因子 [0.8, 1.2)；测试注入定值

    public ModelRouter(ModelRoute primaryRoute, ModelRoute fallbackRoute, int maxCallRetries,
                       Duration backoffBase, Duration backoffMax) {
        this(primaryRoute, fallbackRoute, maxCallRetries, backoffBase, backoffMax,
                () -> 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4);
    }

    public ModelRouter(ModelRoute primaryRoute, ModelRoute fallbackRoute, int maxCallRetries,
                       Duration backoffBase, Duration backoffMax, DoubleSupplier jitter) {
        this.primaryRoute = Objects.requireNonNull(primaryRoute, "primaryRoute");
        this.fallbackRoute = fallbackRoute;
        if (maxCallRetries < 0) {
            throw new IllegalArgumentException("maxCallRetries 不能为负: " + maxCallRetries);
        }
        this.maxCallRetries = maxCallRetries;
        this.backoffBase = Objects.requireNonNull(backoffBase, "backoffBase");
        this.backoffMax = Objects.requireNonNull(backoffMax, "backoffMax");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
    }

    /**
     * 失败后的下一步决策（附录 A.1 决策表 + A.2 闸门）。
     *
     * @param failure             故障分类
     * @param currentRoute        当前路由
     * @param callsOnCurrentRoute 当前路由已发生的物理调用数（单路由 R+1 上限用，附录 B）
     * @param alreadyFallbacked   是否已 fallback 过（G4：一次性）
     * @param budget              本 invocation 预算状态（G1/G2/G3）
     * @param now                 当前时间（注入）
     */
    public RouteDecision decide(ModelCallFailure failure, ModelRoute currentRoute,
                                int callsOnCurrentRoute, boolean alreadyFallbacked,
                                ModelStepBudgetGuard budget, Instant now) {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(currentRoute, "currentRoute");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(now, "now");

        // G1：剩余物理调用预算 ≤ 0 → Fail
        if (!budget.hasRemainingCalls()) {
            return new RouteDecision.Fail("BUDGET_EXHAUSTED", false);
        }
        // G2：deadline 耗尽 → 不等待
        Duration remaining = budget.remainingDeadline(now);
        if (remaining.isZero()) {
            return new RouteDecision.Fail("DEADLINE_EXCEEDED", true);
        }

        // —— 终态族（A1/A2/A3/A4/A13/A14）：不重试不 fallback ——
        if (failure instanceof ModelCallFailure.RequestInvalid) {
            return new RouteDecision.Fail("REQUEST_INVALID", false);
        }
        if (failure instanceof ModelCallFailure.AuthDenied) {
            // A2：异域凭证 fallback 属将来配置变体；矩阵未放行前一律终态
            if (!alreadyFallbacked && canFallback(FaultScope.CREDENTIAL, currentRoute)) {
                return new RouteDecision.Fallback(fallbackRoute);
            }
            return new RouteDecision.Fail("AUTH_DENIED", false);
        }
        if (failure instanceof ModelCallFailure.BillingOrActivation) {
            return new RouteDecision.Fail("BILLING_OR_ACTIVATION", false);
        }
        if (failure instanceof ModelCallFailure.QuotaExhausted) {
            // A4：仅不同 quota_scope 允许
            if (!alreadyFallbacked && canFallback(FaultScope.ACCOUNT, currentRoute)) {
                return new RouteDecision.Fallback(fallbackRoute);
            }
            return new RouteDecision.Fail("QUOTA_EXHAUSTED", false);
        }
        if (failure instanceof ModelCallFailure.ProtocolError) {
            return new RouteDecision.Fail("PROTOCOL_ERROR", false);
        }
        if (failure instanceof ModelCallFailure.UnknownError unknown) {
            return new RouteDecision.Fail("UNKNOWN_ERROR:" + unknown.reason(), false);
        }

        // —— 可重试族（A5~A12）——
        FaultScope scope = failure.faultScope();
        Duration desiredWait = desiredWait(failure, callsOnCurrentRoute, now);

        // A11：本地超时不原地重试（刚超时就原地重打，多半再超时——白烧一次）
        boolean sameRouteAllowed = !(failure instanceof ModelCallFailure.Timeout t && !t.remote());
        // 附录 B：单路由物理调用上限 R+1（callsOnCurrentRoute 已含刚失败这次）
        boolean routeRetriesLeft = callsOnCurrentRoute < maxCallRetries + 1;

        if (sameRouteAllowed && routeRetriesLeft
                && !budget.exceedsInlineDelay(desiredWait) && desiredWait.compareTo(remaining) <= 0) {
            return new RouteDecision.RetrySameRoute(desiredWait);
        }

        // 同路由不再等：有资格 fallback 则切（G3/G2/预算耗尽路径统一收口）
        if (!alreadyFallbacked && canFallback(scope, currentRoute)) {
            return new RouteDecision.Fallback(fallbackRoute);
        }
        // G4/G3：可重试族降级 Defer，不占 Worker 线程长睡
        Instant notBefore = failure instanceof ModelCallFailure.QuotaTemporary qt
                ? qt.notBefore() : now.plus(desiredWait);
        return new RouteDecision.Defer(notBefore);
    }

    /** 各族期望等待时长（有头等够，无头指数退避+抖动，1s 起步 60s 上限——F-11 官方基准） */
    private Duration desiredWait(ModelCallFailure failure, int callsOnCurrentRoute, Instant now) {
        if (failure instanceof ModelCallFailure.QuotaTemporary qt) {
            Duration wait = Duration.between(now, qt.notBefore());
            return wait.isNegative() ? Duration.ZERO : wait;
        }
        if (failure instanceof ModelCallFailure.RateLimitedTransient rl && rl.retryAfter() != null) {
            return rl.retryAfter();
        }
        if (failure instanceof ModelCallFailure.NetworkError) {
            return backoffBase; // A12：短退避
        }
        return exponentialBackoff(callsOnCurrentRoute);
    }

    /** 指数退避 + 抖动：base × 2^n，封顶 backoffMax（F-11：1s 起步、60s 上限） */
    private Duration exponentialBackoff(int callsSoFar) {
        long baseMs = backoffBase.toMillis();
        long shift = Math.min(Math.max(callsSoFar - 1, 0), 6);
        long exponential = baseMs * (1L << shift);
        long capped = Math.min(exponential, backoffMax.toMillis());
        return Duration.ofMillis((long) (capped * jitter.getAsDouble()));
    }

    /**
     * fallback 资格矩阵（§4.3/I36，唯一判定处）：
     * MODEL 域同域允许（换模型有效，裁定 C-2 下唯一放行的同域族）；
     * ENDPOINT 必须异 endpoint_scope；ACCOUNT 必须异 quota_scope；
     * CREDENTIAL 必须异 credential_domain；域不确定 → 禁止（保守）。
     */
    public boolean canFallback(FaultScope scope, ModelRoute currentRoute) {
        if (fallbackRoute == null || scope == null) {
            return false;
        }
        return switch (scope) {
            case MODEL -> true;
            case ENDPOINT -> !currentRoute.endpointScope().equals(fallbackRoute.endpointScope());
            case ACCOUNT -> !currentRoute.quotaScope().equals(fallbackRoute.quotaScope());
            case CREDENTIAL -> !currentRoute.credentialDomain().equals(fallbackRoute.credentialDomain());
        };
    }

    /** 熔断计数资格（§4.5：只计触网后失败；ClientError 族/预算类/未知不累加也不清零） */
    public static boolean countsForBreaker(ModelCallFailure failure) {
        return failure instanceof ModelCallFailure.Timeout
                || failure instanceof ModelCallFailure.NetworkError
                || failure instanceof ModelCallFailure.ProtocolError
                || failure instanceof ModelCallFailure.RateLimitedTransient
                || failure instanceof ModelCallFailure.QuotaTemporary
                || failure instanceof ModelCallFailure.ServerError;
    }

    public ModelRoute primaryRoute() {
        return primaryRoute;
    }

    public ModelRoute fallbackRoute() {
        return fallbackRoute;
    }
}
