package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.ai.BreakerPermit;
import com.objwww.pr.control.domain.ai.CircuitBreaker;
import com.objwww.pr.control.domain.ai.CostCalculation;
import com.objwww.pr.control.domain.ai.FaultScope;
import com.objwww.pr.control.domain.ai.ModelBudgetExceededException;
import com.objwww.pr.control.domain.ai.ModelCallContext;
import com.objwww.pr.control.domain.ai.ModelCallFailedException;
import com.objwww.pr.control.domain.ai.ModelCallFailure;
import com.objwww.pr.control.domain.ai.ModelCallLedgerEntry;
import com.objwww.pr.control.domain.ai.ModelCallLedgerRepository;
import com.objwww.pr.control.domain.ai.ModelGatewayParams;
import com.objwww.pr.control.domain.ai.ModelGatewayPort;
import com.objwww.pr.control.domain.ai.ModelRequest;
import com.objwww.pr.control.domain.ai.ModelResult;
import com.objwww.pr.control.domain.ai.ModelRetryDeferredException;
import com.objwww.pr.control.domain.ai.ModelRoute;
import com.objwww.pr.control.domain.ai.ModelRouteCatalog;
import com.objwww.pr.control.domain.ai.ModelRouteIdentity;
import com.objwww.pr.control.domain.ai.ModelRouter;
import com.objwww.pr.control.domain.ai.ModelStepBudgetGuard;
import com.objwww.pr.control.domain.ai.PricingService;
import com.objwww.pr.control.domain.ai.QuotaCooldownRegistry;
import com.objwww.pr.control.domain.ai.RouteCallOutcome;
import com.objwww.pr.control.domain.ai.RouteClientPort;
import com.objwww.pr.control.domain.ai.RouteDecision;
import com.objwww.pr.control.domain.ai.RoutedModelResult;
import com.objwww.pr.control.domain.ai.TokenUsage;
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.ExecutionEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 模型调用唯一出口（§3.1/I28，附录 A 编号伪代码的唯一合法结构）：
 * 铸 invocation → 预算预检 → 决策循环（G1/G2 闸门 → G5 冷却 → 熔断 → 触网前两段记账
 * → RouteClientPort 至多一次真实 HTTP（I34）→ 终态条件更新 → Router 决策表）→
 * 重试/退避/fallback/Defer；不触网的决策一律走 execution_event 决策事件，不进账本（D14）。
 *
 * <p>预算状态每次 complete() 新建（附录 B）；熔断器/冷却登记为进程内存态（R-M3）。
 * 不标 @Transactional（AFT-30：外部调用不挂数据库长事务）。
 */
public class ModelGateway implements ModelGatewayPort, ModelRouteCatalog {

    private static final Logger log = LoggerFactory.getLogger(ModelGateway.class);
    private static final String PRODUCER = "control-app";
    /** 退避等待的活性检查切片（UT-51：等待期响应取消/租约失效） */
    private static final long SLEEP_SLICE_MS = 100;

    private final ModelRoute primaryRoute;
    private final ModelRoute fallbackRoute; // 可为 null（单路由）
    private final RouteClientPort primaryClient;
    private final RouteClientPort fallbackClient; // 可为 null
    private final ModelGatewayParams params;
    private final ModelCallLedgerRepository ledgerRepository;
    private final PricingService pricingService;
    private final ExecutionLedger eventLedger;
    private final Clock clock;
    private final ModelRouter router;
    private final QuotaCooldownRegistry cooldownRegistry = new QuotaCooldownRegistry();
    private final Map<String, CircuitBreaker> breakers = new HashMap<>();

    public ModelGateway(ModelRoute primaryRoute, ModelRoute fallbackRoute,
                        RouteClientPort primaryClient, RouteClientPort fallbackClient,
                        ModelGatewayParams params, ModelCallLedgerRepository ledgerRepository,
                        PricingService pricingService, ExecutionLedger eventLedger) {
        this(primaryRoute, fallbackRoute, primaryClient, fallbackClient, params,
                ledgerRepository, pricingService, eventLedger, Clock.systemUTC());
    }

    /** 全参构造（测试注入 Clock/jitter；breaker 计时默认 System::nanoTime 单调源） */
    public ModelGateway(ModelRoute primaryRoute, ModelRoute fallbackRoute,
                        RouteClientPort primaryClient, RouteClientPort fallbackClient,
                        ModelGatewayParams params, ModelCallLedgerRepository ledgerRepository,
                        PricingService pricingService, ExecutionLedger eventLedger, Clock clock) {
        this.primaryRoute = Objects.requireNonNull(primaryRoute, "primaryRoute");
        this.fallbackRoute = fallbackRoute;
        this.primaryClient = Objects.requireNonNull(primaryClient, "primaryClient");
        this.fallbackClient = fallbackClient;
        if ((fallbackRoute == null) != (fallbackClient == null)) {
            throw new IllegalArgumentException("fallbackRoute 与 fallbackClient 必须同时存在或同时为空");
        }
        this.params = Objects.requireNonNull(params, "params");
        this.ledgerRepository = Objects.requireNonNull(ledgerRepository, "ledgerRepository");
        this.pricingService = Objects.requireNonNull(pricingService, "pricingService");
        this.eventLedger = Objects.requireNonNull(eventLedger, "eventLedger");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.router = new ModelRouter(primaryRoute, fallbackRoute, params.maxCallRetries(),
                params.backoffBase(), params.backoffMax());
        this.breakers.put(primaryRoute.routeId(),
                new CircuitBreaker(params.failureThreshold(), params.circuitCoolDown().toNanos()));
        if (fallbackRoute != null) {
            this.breakers.put(fallbackRoute.routeId(),
                    new CircuitBreaker(params.failureThreshold(), params.circuitCoolDown().toNanos()));
        }
    }

    // ------------------------------------------------------------------ ModelRouteCatalog

    @Override
    public Optional<ModelRouteIdentity> findContractIdentityByModel(String requestedModel) {
        if (primaryRoute.requestedModel().equals(requestedModel)) {
            return Optional.of(params.contractIdentityOf(primaryRoute));
        }
        if (fallbackRoute != null && fallbackRoute.requestedModel().equals(requestedModel)) {
            return Optional.of(params.contractIdentityOf(fallbackRoute));
        }
        return Optional.empty(); // 路由已被配置移除 → ROUTE_REMOVED（§4.7 规则 2）
    }

    // ------------------------------------------------------------------ ModelGatewayPort

    @Override
    public RoutedModelResult complete(ModelRequest request, ModelCallContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");

        // 1. 铸 invocationId；新建预算状态（本 complete() 独占，附录 B）
        UUID invocationId = UUID.randomUUID();
        ModelStepBudgetGuard budget = new ModelStepBudgetGuard(
                params.maxPhysicalCallsPerStep(), params.maxPromptTokensPerCall(),
                params.maxCompletionTokensPerCall(), params.maxTotalTokensPerStep(),
                params.gatewayTotalDeadline(), params.inlineRetryMaxDelay(), clock.instant());

        // 2. 预算预检（零触网零账本行）：prompt 估算 + completion 申请上限（M0 守卫语义并入）
        int estimatedPrompt = request.prompt().length() / 4; // 粗估：~4 字符/token
        if (estimatedPrompt > params.maxPromptTokensPerCall()
                || request.maxTokens() > params.maxCompletionTokensPerCall()) {
            emitDecisionEvent(ExecutionEventType.MODEL_BUDGET_REJECTED, context, invocationId,
                    primaryRoute, null, "PRE_CHECK", null);
            throw new ModelBudgetExceededException("预算预检拒绝: estimatedPrompt=" + estimatedPrompt
                    + " maxTokens=" + request.maxTokens());
        }

        // 3. 决策循环（附录 A 步骤 4）
        ModelRoute currentRoute = primaryRoute;
        RouteClientPort currentClient = primaryClient;
        String fallbackFrom = null;
        boolean alreadyFallbacked = false;
        int callSeq = 0;
        int callsOnRoute = 0;

        while (true) {
            Instant now = clock.instant();

            // G1：物理调用预算
            if (!budget.hasRemainingCalls()) {
                emitDecisionEvent(ExecutionEventType.MODEL_BUDGET_REJECTED, context, invocationId,
                        currentRoute, null, "BUDGET_EXHAUSTED", null);
                throw new ModelCallFailedException("BUDGET_EXHAUSTED",
                        "物理调用预算耗尽（上限 " + params.maxPhysicalCallsPerStep() + "）", false);
            }
            // G2：总 deadline（含一切等待——退避 sleep 也占额度，F-14/F-15）
            Duration remaining = budget.remainingDeadline(now);
            if (remaining.isZero()) {
                throw new ModelCallFailedException("DEADLINE_EXCEEDED", "Gateway 总 deadline 耗尽", true);
            }

            // G5：配额域冷却（路由选择阶段）：冷却中 → 快败跳过/Defer，notBefore 取登记值
            Instant coolingUntil = cooldownRegistry.coolingUntil(currentRoute.quotaScope(), now);
            if (coolingUntil != null) {
                if (!alreadyFallbacked && fallbackRoute != null
                        && !cooldownRegistry.isCooling(fallbackRoute.quotaScope(), now)) {
                    emitDecisionEvent(ExecutionEventType.MODEL_FALLBACK_SELECTED, context, invocationId,
                            fallbackRoute, null, "QUOTA_COOLING", currentRoute.routeId());
                    fallbackFrom = currentRoute.routeId();
                    currentRoute = fallbackRoute;
                    currentClient = fallbackClient;
                    callsOnRoute = 0;
                    alreadyFallbacked = true;
                    continue;
                }
                emitDecisionEvent(ExecutionEventType.MODEL_RETRY_DEFERRED, context, invocationId,
                        currentRoute, FaultScope.ACCOUNT, "QUOTA_COOLING", coolingUntil);
                throw new ModelRetryDeferredException(coolingUntil, "配额域冷却中");
            }

            // 4.3 熔断检查（A16：快败沿用 OPEN 原因域过矩阵；恰好一发探针由 permit 保证）
            CircuitBreaker breaker = breakers.get(currentRoute.routeId());
            BreakerPermit permit = breaker.tryAcquire();
            if (permit == null) {
                FaultScope openScope = breaker.openedScope();
                emitDecisionEvent(ExecutionEventType.MODEL_CIRCUIT_OPEN_REJECT, context, invocationId,
                        currentRoute, openScope, "BREAKER_OPEN", null);
                if (!alreadyFallbacked && router.canFallback(openScope, currentRoute)) {
                    emitDecisionEvent(ExecutionEventType.MODEL_FALLBACK_SELECTED, context, invocationId,
                            fallbackRoute, openScope, "BREAKER_OPEN", currentRoute.routeId());
                    fallbackFrom = currentRoute.routeId();
                    currentRoute = fallbackRoute;
                    currentClient = fallbackClient;
                    callsOnRoute = 0;
                    alreadyFallbacked = true;
                    continue;
                }
                // A16：attempt 层节奏接管（防双 OPEN 时 Step 即死——E2E-60）
                throw new ModelCallFailedException("CIRCUIT_OPEN", "熔断 OPEN 且无合格 fallback 路由", true);
            }

            callSeq++;
            callsOnRoute++;
            budget.recordCallStarted();

            // 4.4 账本行 id 预铸 → INSERT STARTED（写失败 = 零触网，D5 闸门）
            UUID ledgerId = UUID.randomUUID();
            RouteCallOutcome outcome;
            try (permit) { // permit 归还由 try-with-resources 兜底（F-9：finally 归还防泄漏）
                ModelCallLedgerEntry entry = ModelCallLedgerEntry.builder()
                        .id(ledgerId).invocationId(invocationId).callSeq(callSeq)
                        .reviewRunId(context.runId()).runStepId(context.stepId())
                        .attemptId(context.attemptId()).leaseEpoch(context.leaseEpoch())
                        .routeId(currentRoute.routeId())
                        .routeRole(fallbackFrom == null ? "PRIMARY" : "FALLBACK")
                        .fallbackFrom(fallbackFrom)
                        .endpointScope(currentRoute.endpointScope())
                        .quotaScope(currentRoute.quotaScope())
                        .requestedModel(currentRoute.requestedModel())
                        .build();
                try {
                    ledgerRepository.insertStarted(entry);
                } catch (RuntimeException e) {
                    log.warn("账本 STARTED 写失败，零触网（D5）: {}", e.getClass().getSimpleName());
                    throw new ModelCallFailedException("LEDGER_WRITE_FAILED", "账本 STARTED 写入失败", true);
                }

                // 4.5 perCallTimeout = min(配置值, 剩余 deadline)——剩余毫秒逐层下传
                Duration perCallTimeout = remaining.compareTo(params.perCallTimeout()) < 0
                        ? remaining : params.perCallTimeout();
                outcome = currentClient.complete(request, perCallTimeout);

                if (outcome instanceof RouteCallOutcome.Ok) {
                    permit.onSuccess();
                } else if (outcome instanceof RouteCallOutcome.Failed f
                        && ModelRouter.countsForBreaker(f.failure())) {
                    permit.onFailure(f.failure().faultScope());
                }
                // ClientError 族/未知：onSuccess/onFailure 都不调，close() 中性归还（不累加不清零）
            }

            // 4.6 成功分支
            if (outcome instanceof RouteCallOutcome.Ok ok) {
                TokenUsage usage = ok.usage();
                boolean usageMissing = ok.usageMissing();
                if (!usageMissing && (usage.promptTokens() < 0 || usage.completionTokens() < 0
                        || usage.totalTokens() < 0)) {
                    // EX-58：负数 usage 不可信——按缺失处理，不进成本计算
                    log.warn("供应商返回负数 usage，按 usage_missing 落账 route={}", currentRoute.routeId());
                    usage = new TokenUsage(0, 0, 0);
                    usageMissing = true;
                }
                CostCalculation cost = pricingService.calculate(
                        currentRoute.requestedModel(), usage, usageMissing);
                try {
                    ledgerRepository.completeTerminalSuccess(ledgerId, usage, usageMissing,
                            ok.reportedModel(), ok.providerRequestId(), ok.latency(),
                            cost.costMicros(), cost.pricingVersion(), cost.currency(),
                            cost.inputPriceMicrosPer1k(), cost.outputPriceMicrosPer1k());
                } catch (RuntimeException e) {
                    // 终态写失败：行滞留 STARTED → Recovery 标 UNKNOWN；Step FAILED，不付费重试（§4.6）
                    log.warn("账本终态写失败（SUCCEEDED），转 UNKNOWN 语义: {}", e.getClass().getSimpleName());
                    throw new ModelCallFailedException("LEDGER_WRITE_FAILED", "账本终态更新失败", true);
                }

                // post-call 预算结算：usage 已落账（钱已花，事实先记），超限再丢弃结果（阻断项 10）
                if (!usageMissing) {
                    try {
                        budget.recordUsageAndCheck(usage);
                    } catch (ModelBudgetExceededException e) {
                        emitDecisionEvent(ExecutionEventType.MODEL_BUDGET_REJECTED, context,
                                invocationId, currentRoute, null, "POST_CALL_TOTAL_TOKENS", null);
                        throw e;
                    }
                }

                return new RoutedModelResult(
                        new ModelResult(ok.content(), usage,
                                ok.reportedModel() != null ? ok.reportedModel()
                                        : currentRoute.requestedModel()),
                        currentRoute, params.contractIdentityOf(currentRoute),
                        invocationId, callSeq, fallbackFrom, usageMissing, ok.latency());
            }

            // 4.7 失败分支
            RouteCallOutcome.Failed failed = (RouteCallOutcome.Failed) outcome;
            ModelCallFailure failure = failed.failure();
            String outcomeName = outcomeName(failure);
            String errorCode = failed.providerCode() != null ? failed.providerCode() : outcomeName;
            try {
                ledgerRepository.completeTerminalFailure(ledgerId, outcomeName, failed.httpStatus(),
                        failed.retryAfter(), failed.latency(), errorCode,
                        fingerprint(outcomeName, errorCode, failed.httpStatus()),
                        "Model call failed: " + outcomeName); // 脱敏由构造保证：故障对象不携带供应商原文
            } catch (RuntimeException e) {
                log.warn("账本终态写失败（FAILED），转 UNKNOWN 语义: {}", e.getClass().getSimpleName());
                throw new ModelCallFailedException("LEDGER_WRITE_FAILED", "账本终态更新失败", true);
            }

            // §4.5 配额域共享冷却：账号级故障终态落账后写冷却
            if (failure.faultScope() == FaultScope.ACCOUNT) {
                Instant coolUntil = failure instanceof ModelCallFailure.QuotaTemporary qt
                        ? qt.notBefore() : now.plus(params.backoffMax());
                cooldownRegistry.markCoolingUntil(currentRoute.quotaScope(), coolUntil);
            }
            // A3：欠费/未开通单列告警（不淹没在普通失败里；终态，errorCode 进 STEP_RESULT）
            if (failure instanceof ModelCallFailure.BillingOrActivation) {
                log.warn("BILLING_ALERT: 模型账号欠费或未开通服务，route={}", currentRoute.routeId());
            }

            RouteDecision decision = router.decide(failure, currentRoute, callsOnRoute,
                    alreadyFallbacked, budget, now);
            switch (decision) {
                case RouteDecision.RetrySameRoute retry -> sleepCancellable(retry.backoff(), context);
                case RouteDecision.Fallback fb -> {
                    emitDecisionEvent(ExecutionEventType.MODEL_FALLBACK_SELECTED, context, invocationId,
                            fb.nextRoute(), failure.faultScope(), outcomeName, currentRoute.routeId());
                    fallbackFrom = currentRoute.routeId();
                    currentRoute = fb.nextRoute();
                    currentClient = fallbackClient;
                    callsOnRoute = 0;
                    alreadyFallbacked = true;
                }
                case RouteDecision.Defer defer -> {
                    emitDecisionEvent(ExecutionEventType.MODEL_RETRY_DEFERRED, context, invocationId,
                            currentRoute, failure.faultScope(), outcomeName, defer.notBefore());
                    throw new ModelRetryDeferredException(defer.notBefore(),
                            "长等待挂回队列: " + outcomeName);
                }
                case RouteDecision.Fail fail ->
                        throw new ModelCallFailedException(fail.reason(),
                                "模型调用终态失败: " + fail.reason(), fail.stepRetryable());
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 退避等待（切片睡眠）：响应中断与租约失效（UT-51）；不越过 inline 上限由 Router 保证 */
    private void sleepCancellable(Duration duration, ModelCallContext context) {
        long remainingMs = duration.toMillis();
        while (remainingMs > 0) {
            if (Thread.currentThread().isInterrupted()) {
                throw new ModelCallFailedException("INTERRUPTED", "退避等待被中断", true);
            }
            if (!context.leaseHeartbeat().getAsBoolean()) {
                throw new ModelCallFailedException("LEASE_LOST", "退避等待期间租约失效", false);
            }
            long slice = Math.min(remainingMs, SLEEP_SLICE_MS);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModelCallFailedException("INTERRUPTED", "退避等待被中断", true);
            }
            remainingMs -= slice;
        }
    }

    /** 决策事件（附录 C payload：route_id/invocation_id/fault_scope/reason [+not_before/from/to]） */
    private void emitDecisionEvent(ExecutionEventType type, ModelCallContext context, UUID invocationId,
                                   ModelRoute route, FaultScope faultScope, String reason,
                                   Object extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("route_id", route.routeId());
        payload.put("invocation_id", invocationId.toString());
        if (faultScope != null) {
            payload.put("fault_scope", faultScope.name());
        }
        payload.put("reason", reason);
        if (type == ExecutionEventType.MODEL_RETRY_DEFERRED && extra instanceof Instant notBefore) {
            payload.put("not_before", notBefore.toString());
        }
        if (type == ExecutionEventType.MODEL_FALLBACK_SELECTED && extra instanceof String fromRoute) {
            payload.put("from_route", fromRoute);
            payload.put("to_route", route.routeId());
        }
        eventLedger.append(eventLedger.newEvent(context.runId(), context.prRevisionId(),
                context.stepId(), context.attemptId(), type, null, context.runId(), PRODUCER, payload));
    }

    private static String outcomeName(ModelCallFailure failure) {
        return switch (failure) {
            case ModelCallFailure.Timeout ignored -> "TIMEOUT";
            case ModelCallFailure.NetworkError ignored -> "NETWORK_ERROR";
            case ModelCallFailure.ProtocolError ignored -> "PROTOCOL_ERROR";
            case ModelCallFailure.RateLimitedTransient ignored -> "RATE_LIMITED_TRANSIENT";
            case ModelCallFailure.QuotaTemporary ignored -> "QUOTA_TEMPORARY";
            case ModelCallFailure.QuotaExhausted ignored -> "QUOTA_EXHAUSTED";
            case ModelCallFailure.BillingOrActivation ignored -> "BILLING_OR_ACTIVATION";
            case ModelCallFailure.AuthDenied ignored -> "AUTH_DENIED";
            case ModelCallFailure.RequestInvalid ignored -> "REQUEST_INVALID";
            case ModelCallFailure.ServerError ignored -> "SERVER_ERROR";
            case ModelCallFailure.UnknownError ignored -> "UNKNOWN_ERROR";
        };
    }

    /** 聚类指纹：脱敏后的稳定三元组 digest（§4.11 error_fingerprint） */
    private static String fingerprint(String outcome, String errorCode, Integer httpStatus) {
        return Digest.sha256Of(outcome + "|" + errorCode + "|" + httpStatus).value();
    }
}
