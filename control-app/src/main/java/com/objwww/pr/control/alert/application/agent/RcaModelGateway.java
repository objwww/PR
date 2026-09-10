package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.application.ModelGateway;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallContext;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallException;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger;
import com.objwww.pr.control.alert.domain.agent.RcaModelOutcome;
import com.objwww.pr.control.domain.ai.CostCalculation;
import com.objwww.pr.control.domain.ai.ModelBudgetExceededException;
import com.objwww.pr.control.domain.ai.ModelCallContext;
import com.objwww.pr.control.domain.ai.ModelCallFailedException;
import com.objwww.pr.control.domain.ai.ModelRequest;
import com.objwww.pr.control.domain.ai.ModelRetryDeferredException;
import com.objwww.pr.control.domain.ai.PricingService;
import com.objwww.pr.control.domain.ai.RoutedModelResult;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * RCA 模型网关适配（R7a-1，v2.1 §二）：复用既有 {@link ModelGateway} 的路由/错误
 * 分类/重试/熔断面（不新抽路由器，§二.1），仅新增 RCA 账本与上下文适配——
 * <ol>
 *   <li><b>账本不可写 = 零触网</b>：rca_model_call PENDING 先行落账取得发送资格
 *       （open 抛异常 → 不进 gateway.complete）；</li>
 *   <li>成功终态带 usage/cost 落账；usage 缺失不猜零（SUCCESS + usageMissing，
 *       费用未决走对账，RX19）；供应商回执（provider_request_id）留平台账本，
 *       经 invocation_id 跨账关联，RCA 账本不重复落；</li>
 *   <li>DEFERRED/BUDGET/确定性失败 → FAILED+原因码；平台 LEDGER_WRITE_FAILED →
 *       UNKNOWN（是否已执行不确定，保守占预算，恢复对账不盲重发）。</li>
 * </ol>
 * 预算预留/结算（TOOL_CALL/TOKEN 维的 reserve/commit/release）归 ActionGuard
 * （R7a-2，§六固定顺序），本类只携带 budgetReservationId 入账。
 *
 * <p>gateway 实例须以 RCA 侧事件汇构造（rca_event，无 pr_revision FK 面）——
 * 装配见 X6（AlertAm4Config）；本类不重复路由决策，异常原因码透传平台语义。
 */
public class RcaModelGateway {

    private static final Logger log = LoggerFactory.getLogger(RcaModelGateway.class);

    private final ModelGateway gateway;
    private final RcaModelCallLedger ledger;
    private final PricingService pricing;
    private final Clock clock;

    /** RCA 侧组装（gateway 必须挂 RcaModelEventSink 事件汇——由装配点保证） */
    public RcaModelGateway(ModelGateway gateway, RcaModelCallLedger ledger,
            PricingService pricing, Clock clock) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.pricing = Objects.requireNonNull(pricing, "pricing");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 一次逻辑模型动作（平台内联物理重试在平台账本逐行记账，RCA 账本每逻辑动作
     * 一行 physical_seq=1，经 invocation_id 关联跨账对账）。
     *
     * @throws RcaModelCallException 封闭原因码；LEDGER_WRITE_FAILED 时零触网成立
     */
    public RcaModelOutcome call(RcaModelCallContext ctx, String prompt, int maxTokens) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(prompt, "prompt");
        UUID operationId = UUID.randomUUID();
        String promptDigest = Digest.sha256Of(prompt).value();
        try {
            ledger.open(new RcaModelCallLedger.OpenRow(operationId, ctx.runId(),
                    ctx.taskId(), ctx.attemptId(), ctx.actionSeq(), 1, ctx.roundId(),
                    ctx.roleId(), ctx.roleVersion(), ctx.roleDigest(), promptDigest,
                    ctx.budgetReservationId(), ctx.inputSnapshotDigest(), ctx.configEpoch(),
                    ctx.releaseDigest(), ctx.leaseEpoch()));
        } catch (RuntimeException e) {
            log.warn("rca_model_call PENDING 写失败，零触网（{}）: {}",
                    ctx.roleId(), e.getClass().getSimpleName());
            throw new RcaModelCallException("LEDGER_WRITE_FAILED",
                    "模型账本不可写，零触网", false, true, e);
        }

        Instant deadline = ctx.effectiveDeadline();
        ModelCallContext platform = new ModelCallContext(ctx.runId(), ctx.runId(),
                ctx.taskId(), ctx.attemptId(), ctx.leaseEpoch(), deadline,
                ctx.leaseHeartbeat());
        Duration requestTimeout = Duration.between(clock.instant(), deadline);
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            ledger.fail(operationId, "DEADLINE_EXCEEDED");
            throw new RcaModelCallException("DEADLINE_EXCEEDED",
                    "run/task deadline 已耗尽，未发送", false, true, null);
        }
        try {
            RoutedModelResult r = gateway.complete(
                    new ModelRequest(prompt, maxTokens, requestTimeout), platform);
            CostCalculation cost = pricing.calculate(r.route().requestedModel(),
                    r.result().tokenUsage(), r.usageMissing());
            boolean settled = ledger.succeed(operationId,
                    new RcaModelCallLedger.UsageOutcome(
                            r.result().tokenUsage().promptTokens(),
                            r.result().tokenUsage().completionTokens(),
                            r.result().tokenUsage().totalTokens(),
                            r.usageMissing(),
                            r.usageMissing() ? null : cost.costMicros(),
                            r.usageMissing() ? null : cost.pricingVersion(),
                            r.usageMissing() ? null : cost.currency(),
                            null,
                            r.route().routeId(),
                            r.result().actualModel(),
                            r.latency().toMillis(),
                            r.invocationId()));
            if (!settled) {
                throw new RcaModelCallException("LEDGER_SETTLE_RACE",
                        "成功终态 CAS 落空（单写者纪律破坏）", false);
            }
            return new RcaModelOutcome(operationId, r.result().content(),
                    r.result().actualModel(), r.result().tokenUsage().totalTokens(),
                    r.usageMissing(), r.route().routeId(), r.invocationId());
        } catch (ModelRetryDeferredException e) {
            ledger.fail(operationId, "DEFERRED");
            throw new RcaModelCallException("DEFERRED",
                    "长等待挂回队列: " + e.getMessage(), true, e);
        } catch (ModelBudgetExceededException e) {
            ledger.fail(operationId, "BUDGET_EXCEEDED");
            throw new RcaModelCallException("BUDGET_EXCEEDED",
                    "平台预算预检/后检拒绝: " + e.getMessage(), false, e);
        } catch (ModelCallFailedException e) {
            if ("LEDGER_WRITE_FAILED".equals(e.errorCode())) {
                // 平台账本面不可写：已发送与否不确定 → UNKNOWN 保守占预算（对账不盲重发）
                ledger.markUnknown(operationId);
            } else {
                ledger.fail(operationId, e.errorCode());
            }
            throw new RcaModelCallException(e.errorCode(), e.getMessage(),
                    e.stepRetryable(), e);
        }
    }
}
