package com.objwww.pr.arena.application;

import com.objwww.pr.arena.application.chaos.FaultGate;
import com.objwww.pr.arena.application.chaos.FaultType;
import com.objwww.pr.arena.domain.model.BookingStatus;
import com.objwww.pr.arena.domain.model.IdempotencyClaim;
import com.objwww.pr.arena.domain.model.PayStatus;
import com.objwww.pr.arena.domain.model.PaymentResult;
import com.objwww.pr.arena.domain.model.RefundParty;
import com.objwww.pr.arena.domain.model.ResourceType;
import com.objwww.pr.arena.domain.model.TradeOrder;
import com.objwww.pr.arena.domain.repository.TradeOrderRepository;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 两步创单编排（M2-09/10）+ 取消/退款一致性（M2-11）+ F1 注入点（M2-18）。
 *
 * <p>流程：幂等 claim（F1 命中且 chaos- 流量时整体跳过）→ CREATE 快照 → 逐资源短事务扣减
 * → 支付授权（结果映射）→ ENABLE / DISCARDED / UNKNOWN 挂起。
 *
 * <p>确定性结果映射（M2-10 四路径）：
 * 成功 → ENABLED；拒绝（sku*-declined）→ DISCARDED(PAYMENT_DECLINED)；
 * 未知（F3 命中）→ CREATED 挂起 + AUTH=UNKNOWN（禁 sleep，状态持久化）；
 * 迟到成功 = 对账路径 UNKNOWN→SUCCEEDED 后补 ENABLE（F3ReconcileService）。
 *
 * <p>幂等完成语义：Created/Discarded/PendingReconciliation 均以订单号收口 CONSUMED——
 * 重放返回同一订单（Pending 期间订单对查询不可见属预期，恢复后可见）。
 */
public class TwoStepOrderService {

    private static final Logger log = LoggerFactory.getLogger(TwoStepOrderService.class);

    /** 创单结果（封闭类型，接口层映射 HTTP 语义） */
    public sealed interface CreateOutcome {
        record Created(UUID orderId) implements CreateOutcome {
        }

        record Discarded(UUID orderId, String reason) implements CreateOutcome {
        }

        /** F3：订单停留 CREATED（查询不可见），等待 F3ReconcileService 对账 */
        record PendingReconciliation(UUID orderId) implements CreateOutcome {
        }

        /** F10：支付沉默——AUTH 停 INITIATED（无结果行，区别于 F3 UNKNOWN），订单不可见 */
        record SilentPending(UUID orderId) implements CreateOutcome {
        }

        /** F16：入口静默——请求被吞没不落单（流量面看似被接受） */
        record IngressSilent() implements CreateOutcome {
        }

        record Replayed(UUID orderId, boolean discarded) implements CreateOutcome {
        }

        record Processing() implements CreateOutcome {
        }

        record Conflict() implements CreateOutcome {
        }
    }

    public sealed interface PayOutcome {
        record Paid(UUID orderId) implements PayOutcome {
        }

        record Declined(UUID orderId) implements PayOutcome {
        }

        record Pending(UUID orderId) implements PayOutcome {
        }

        record NotFound(UUID orderId) implements PayOutcome {
        }

        record Illegal(UUID orderId, String detail) implements PayOutcome {
        }
    }

    public sealed interface CancelOutcome {
        record Cancelled(UUID orderId) implements CancelOutcome {
        }

        record RefundCancelled(UUID orderId, UUID refundId) implements CancelOutcome {
        }

        record NotFound(UUID orderId) implements CancelOutcome {
        }

        record Illegal(UUID orderId, String detail) implements CancelOutcome {
        }
    }

    /** 幂等窄接口（装配期绑定 PostgresIdempotencyRepository 的适配器） */
    public interface IdempotencyOperations {
        IdempotencyClaim claim(String intentId, String requestDigest);

        boolean complete(String intentId, long leaseEpoch, UUID resultOrderId, String responseDigest);
    }

    private final OrderCreationSteps steps;
    private final PaymentGatewaySimulator gateway;
    private final FaultGate faultGate;
    private final TradeOrderRepository tradeOrders;
    private final RefundChainService refundChain;
    private final IdempotencyOperations idempotency;

    public TwoStepOrderService(OrderCreationSteps steps, PaymentGatewaySimulator gateway,
                               FaultGate faultGate, TradeOrderRepository tradeOrders,
                               RefundChainService refundChain,
                               IdempotencyOperations idempotency) {
        this.steps = steps;
        this.gateway = gateway;
        this.faultGate = faultGate;
        this.tradeOrders = tradeOrders;
        this.refundChain = refundChain;
        this.idempotency = idempotency;
    }

    public CreateOutcome create(String intentId, String correlationId, String buyerId,
                                String sku, int quantity, BigDecimal amount) {
        validateCorrelation(correlationId);
        // M-a F16：入口静默——请求吞没不落单（流量面看似被接受，oa_orders_created 不再增长）
        if (correlationId.startsWith("chaos-")
                && faultGate.active(FaultType.F16, correlationId)) {
            log.warn("F16 入口静默：请求吞没不落单: intent={}", intentId);
            return new CreateOutcome.IngressSilent();
        }
        // INV-AM2-1：故障只对 chaos- 前缀生效（live 流量永不跳过幂等）
        boolean f1Active = correlationId.startsWith("chaos-")
                && faultGate.active(FaultType.F1, correlationId);
        if (f1Active) {
            log.warn("F1 幂等闸旁路：跳过幂等 claim，同 intent 可重复创单: intent={} correlation={}",
                    intentId, correlationId);
        }

        long epoch = -1;
        if (!f1Active) {
            String requestDigest = Digest.sha256Of(intentId + "|" + buyerId + "|" + sku + "|"
                    + quantity + "|" + amount.toPlainString()).value();
            IdempotencyClaim claim = idempotency.claim(intentId, requestDigest);
            switch (claim) {
                case IdempotencyClaim.Claimed c -> epoch = c.leaseEpoch();
                case IdempotencyClaim.Replay r -> {
                    return replayOutcome(r.resultOrderId());
                }
                case IdempotencyClaim.InProgress i -> {
                    return new CreateOutcome.Processing();
                }
                case IdempotencyClaim.Conflict c -> {
                    return new CreateOutcome.Conflict();
                }
            }
        }
        return driveCreation(intentId, correlationId, buyerId, sku, quantity, amount, epoch);
    }

    private CreateOutcome driveCreation(String intentId, String correlationId, String buyerId,
                                        String sku, int quantity, BigDecimal amount, long epoch) {
        UUID orderId = UUID.randomUUID();
        CreateOutcome outcome = runSteps(intentId, correlationId, buyerId, sku, quantity,
                amount, orderId);
        if (epoch >= 0 && outcomeConsumed(outcome)) {
            UUID consumedOrder = switch (outcome) {
                case CreateOutcome.Created c -> c.orderId();
                case CreateOutcome.Discarded d -> d.orderId();
                case CreateOutcome.PendingReconciliation p -> p.orderId();
                default -> null;
            };
            String finalState = switch (outcome) {
                case CreateOutcome.Discarded d -> d.reason();
                case CreateOutcome.PendingReconciliation p -> "PENDING";
                default -> "ENABLED";
            };
            idempotency.complete(intentId, epoch, consumedOrder,
                    Digest.sha256Of(finalState).value());
        }
        return outcome;
    }

    private boolean outcomeConsumed(CreateOutcome outcome) {
        return outcome instanceof CreateOutcome.Created
                || outcome instanceof CreateOutcome.Discarded
                || outcome instanceof CreateOutcome.PendingReconciliation;
    }

    private CreateOutcome runSteps(String intentId, String correlationId, String buyerId,
                                   String sku, int quantity, BigDecimal amount, UUID orderId) {
        // M-a：F10/F12/F13/F14 激活判定（与 F1 同款双条件——chaos- 前缀 + 会话在场，INV-AM2-1）
        boolean f10Silent = correlationId.startsWith("chaos-")
                && faultGate.active(FaultType.F10, correlationId);
        boolean f12Skew = correlationId.startsWith("chaos-")
                && faultGate.active(FaultType.F12, correlationId);
        boolean f13Oversell = correlationId.startsWith("chaos-")
                && faultGate.active(FaultType.F13, correlationId);
        boolean f14Lost = correlationId.startsWith("chaos-")
                && faultGate.active(FaultType.F14, correlationId);

        TradeOrder newOrder = TradeOrder.create(orderId, intentId, correlationId, buyerId,
                sku, quantity, amount);
        var snapshot = steps.createSnapshotTx(newOrder);
        if (f14Lost) {
            // F14 消息丢失：履约行删除（下游从未感知的无痕丢失，S21）
            steps.dropFulfillmentTx(orderId);
        }

        // 逐资源独立短事务扣减（NO_ROOM 即停，进入废单补偿）
        ResourceType[] plan = ResourceType.values();
        for (int i = 0; i < plan.length; i++) {
            // F12 库存偏差：跳过 DISCOUNT 资源的台账扣减（订单推进但记账缺失 → 对账不平）
            if (f12Skew && plan[i] == ResourceType.DISCOUNT) {
                continue;
            }
            try {
                steps.deductResourceTx(snapshot, plan[i], i + 1, quantity);
            } catch (OrderCreationSteps.NoRoomException e) {
                String reason = "NO_ROOM_" + plan[i].name();
                steps.discardTx(orderId, reason);
                return new CreateOutcome.Discarded(orderId, reason);
            }
        }
        if (f13Oversell) {
            // F13 超卖：竞态超额行（同单第二笔 INVENTORY DEDUCT，正常恰一行）
            steps.deductResourceTx(snapshot, ResourceType.INVENTORY,
                    plan.length + 1, quantity);
        }

        // 支付授权：INITIATED 落库（独立事务）→ 进程内网关 → 结果 CAS 落定
        var auth = steps.initiateAuthTx(orderId, amount);
        if (f10Silent) {
            // F10 支付悬挂：网关调用本身沉默——AUTH 停 INITIATED（无结果行），
            // 订单停 CREATED（不可见）→ 待支付积压（区别于 F3 的 UNKNOWN 挂起）
            log.warn("F10 支付沉默：AUTH 停 INITIATED，订单挂起: orderId={}", orderId);
            return new CreateOutcome.SilentPending(orderId);
        }
        PaymentResult result = gateway.authorize(correlationId, sku, amount);
        steps.resolveAuthTx(auth.id(), result);

        return switch (result) {
            case SUCCEEDED -> {
                // F17 履约变慢：只收口交易单，履约停 CONFIRMING（SLA 超时积压，S25）
                boolean f17Slow = correlationId.startsWith("chaos-")
                        && faultGate.active(FaultType.F17, correlationId);
                if (f17Slow) {
                    steps.enableBookingOnlyTx(orderId);
                } else {
                    steps.enableTx(orderId);
                }
                yield new CreateOutcome.Created(orderId);
            }
            case DECLINED -> {
                steps.discardTx(orderId, "PAYMENT_DECLINED");
                yield new CreateOutcome.Discarded(orderId, "PAYMENT_DECLINED");
            }
            case UNKNOWN -> {
                log.warn("支付结果未知，订单挂起 CREATED 等待对账: orderId={}", orderId);
                yield new CreateOutcome.PendingReconciliation(orderId);
            }
            default -> throw new IllegalStateException("创单授权不可能出现 " + result);
        };
    }

    /** C-1：pay() 回调只作用于 ENABLED 订单（NOT_PAY）；F11 命中时放行 PAID 重复扣款 */
    public PayOutcome pay(UUID orderId, String correlationId) {
        validateCorrelation(correlationId);
        // M-a F11：chaos- 前缀 + 会话在场 → 跳过"已支付"闸（支付层幂等失效）
        boolean f11Double = correlationId.startsWith("chaos-")
                && faultGate.active(FaultType.F11, correlationId);
        TradeOrder order = tradeOrders.findById(orderId).orElse(null);
        if (order == null || order.bookingStatus() != BookingStatus.ENABLED
                || (order.payStatus() != PayStatus.NOT_PAY && !f11Double)) {
            return order == null
                    ? new PayOutcome.NotFound(orderId)
                    : new PayOutcome.Illegal(orderId, order.bookingStatus() + "/" + order.payStatus());
        }
        var capture = steps.initiateCaptureTx(orderId, order.amount());
        PaymentResult result = gateway.capture(correlationId, order.sku(), order.amount());
        steps.resolveCaptureTx(capture.id(), result);
        return switch (result) {
            case SUCCEEDED -> {
                // F9 掉单：支付事实已落（CAPTURE SUCCEEDED），回调后状态推进被吞——订单停 NOT_PAY
                boolean f9Drop = correlationId.startsWith("chaos-")
                        && faultGate.active(FaultType.F9, correlationId);
                if (!f9Drop) {
                    steps.markPaidTx(orderId);
                } else {
                    log.warn("F9 回调丢弃：支付成功但订单状态推进被吞: orderId={}", orderId);
                }
                yield new PayOutcome.Paid(orderId);
            }
            case DECLINED -> new PayOutcome.Declined(orderId);
            case UNKNOWN -> new PayOutcome.Pending(orderId);
            default -> throw new IllegalStateException("CAPTURE 不可能为 " + result);
        };
    }

    /** 取消：NOT_PAY 直接废单补偿；PAID 先退款（责任方分支+金额约束）再废单（M2-11） */
    public CancelOutcome cancel(UUID orderId, String reason, RefundParty party) {
        TradeOrder order = tradeOrders.findById(orderId).orElse(null);
        if (order == null || order.bookingStatus() == BookingStatus.CREATED) {
            return order == null
                    ? new CancelOutcome.NotFound(orderId)
                    : new CancelOutcome.Illegal(orderId, "CREATED 不可见不可取消");
        }
        if (order.bookingStatus() == BookingStatus.DISCARDED) {
            return new CancelOutcome.Illegal(orderId, "已废单");
        }
        if (order.payStatus() == PayStatus.PAID) {
            UUID refundId = refundChain.refundPaid(orderId, order.amount(), reason, party);
            steps.discardEnabledTx(orderId, "REFUND_" + party.name());
            return new CancelOutcome.RefundCancelled(orderId, refundId);
        }
        steps.discardEnabledTx(orderId, "CANCELLED_" + party.name());
        return new CancelOutcome.Cancelled(orderId);
    }

    private CreateOutcome replayOutcome(UUID orderId) {
        boolean discarded = tradeOrders.findById(orderId)
                .map(o -> o.bookingStatus() == BookingStatus.DISCARDED)
                .orElse(true);
        return new CreateOutcome.Replayed(orderId, discarded);
    }

    private void validateCorrelation(String correlationId) {
        if (correlationId == null || !(correlationId.startsWith("live-")
                || correlationId.startsWith("chaos-"))) {
            throw new IllegalArgumentException("correlationId 必须以 live-/chaos- 开头");
        }
    }
}
