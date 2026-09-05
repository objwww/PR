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
        // INV-AM2-1：故障只对 chaos- 前缀生效（live 流量永不跳过幂等）
        boolean f1Active = correlationId.startsWith("chaos-")
                && faultGate.active(FaultType.F1, correlationId);

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
        TradeOrder newOrder = TradeOrder.create(orderId, intentId, correlationId, buyerId,
                sku, quantity, amount);
        var snapshot = steps.createSnapshotTx(newOrder);

        // 逐资源独立短事务扣减（NO_ROOM 即停，进入废单补偿）
        ResourceType[] plan = ResourceType.values();
        for (int i = 0; i < plan.length; i++) {
            try {
                steps.deductResourceTx(snapshot, plan[i], i + 1, quantity);
            } catch (OrderCreationSteps.NoRoomException e) {
                String reason = "NO_ROOM_" + plan[i].name();
                steps.discardTx(orderId, reason);
                return new CreateOutcome.Discarded(orderId, reason);
            }
        }

        // 支付授权：INITIATED 落库（独立事务）→ 进程内网关 → 结果 CAS 落定
        var auth = steps.initiateAuthTx(orderId, amount);
        PaymentResult result = gateway.authorize(correlationId, sku, amount);
        steps.resolveAuthTx(auth.id(), result);

        return switch (result) {
            case SUCCEEDED -> {
                steps.enableTx(orderId);
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

    /** C-1：pay() 回调只作用于 ENABLED 订单（NOT_PAY） */
    public PayOutcome pay(UUID orderId, String correlationId) {
        validateCorrelation(correlationId);
        TradeOrder order = tradeOrders.findById(orderId).orElse(null);
        if (order == null || order.bookingStatus() != BookingStatus.ENABLED
                || order.payStatus() != PayStatus.NOT_PAY) {
            return order == null
                    ? new PayOutcome.NotFound(orderId)
                    : new PayOutcome.Illegal(orderId, order.bookingStatus() + "/" + order.payStatus());
        }
        var capture = steps.initiateCaptureTx(orderId, order.amount());
        PaymentResult result = gateway.capture(correlationId, order.sku(), order.amount());
        steps.resolveCaptureTx(capture.id(), result);
        return switch (result) {
            case SUCCEEDED -> {
                steps.markPaidTx(orderId);
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
