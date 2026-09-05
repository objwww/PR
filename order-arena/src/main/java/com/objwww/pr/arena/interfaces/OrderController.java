package com.objwww.pr.arena.interfaces;

import com.objwww.pr.arena.application.TwoStepOrderService;
import com.objwww.pr.arena.domain.model.RefundParty;
import com.objwww.pr.arena.domain.model.TradeOrder;
import com.objwww.pr.arena.domain.repository.TradeOrderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * 正常业务 API（M2-14，AM2 v3.0 §3.2）：POST /orders、GET /orders/{id}、
 * POST /orders/{id}/pay、POST /orders/{id}/cancel。
 *
 * <p>统一错误契约：400 参数非法 / 404 未知或 CREATED 不可见 / 409 状态冲突或幂等冲突 /
 * 202 幂等处理中（带 Retry-After）。CREATED 订单对查询一律 404（M2-09 不可见语义）。
 * 仅 docker profile 暴露（装配依赖业务链路，沿 control-app 惯例）。
 */
@RestController
@Profile("docker")
public class OrderController {

    private final TwoStepOrderService orders;
    private final TradeOrderRepository tradeOrders;
    private final long retryAfterSeconds;

    public OrderController(TwoStepOrderService orders, TradeOrderRepository tradeOrders,
                           @Value("${app.arena.api.retry-after-seconds:2}") long retryAfterSeconds) {
        this.orders = orders;
        this.tradeOrders = tradeOrders;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public record CreateRequest(String intentId, String correlationId, String buyerId,
                                String sku, Integer quantity, BigDecimal amount) {
    }

    @PostMapping(path = "/orders", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest request) {
        if (request.intentId() == null || request.intentId().isBlank()
                || request.correlationId() == null || request.buyerId() == null
                || request.sku() == null || request.quantity() == null || request.quantity() <= 0
                || request.amount() == null || request.amount().signum() < 0) {
            return badRequest("非法请求参数");
        }
        var outcome = orders.create(request.intentId(), request.correlationId(),
                request.buyerId(), request.sku(), request.quantity(), request.amount());
        return switch (outcome) {
            case TwoStepOrderService.CreateOutcome.Created c ->
                    ResponseEntity.status(201).body(Map.of(
                            "orderId", c.orderId().toString(), "bookingStatus", "ENABLED"));
            case TwoStepOrderService.CreateOutcome.Discarded d ->
                    ResponseEntity.status(201).body(Map.of(
                            "orderId", d.orderId().toString(), "bookingStatus", "DISCARDED",
                            "reason", d.reason()));
            case TwoStepOrderService.CreateOutcome.PendingReconciliation p ->
                    ResponseEntity.status(201).body(Map.of(
                            "orderId", p.orderId().toString(), "bookingStatus", "CREATED",
                            "paymentResult", "UNKNOWN"));
            case TwoStepOrderService.CreateOutcome.Replayed r -> ResponseEntity.ok().body(Map.of(
                    "orderId", r.orderId().toString(), "replayed", true,
                    "bookingStatus", r.discarded() ? "DISCARDED" : "ENABLED"));
            case TwoStepOrderService.CreateOutcome.Processing p -> ResponseEntity.status(202)
                    .header("Retry-After", String.valueOf(retryAfterSeconds))
                    .body(Map.of("status", "PROCESSING"));
            case TwoStepOrderService.CreateOutcome.Conflict c ->
                    conflict("幂等键冲突：同 intent 不同请求");
        };
    }

    @GetMapping(path = "/orders/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("id") String id) {
        UUID orderId;
        try {
            orderId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return badRequest("订单号格式非法");
        }
        return tradeOrders.findVisibleById(orderId)
                .<ResponseEntity<Map<String, Object>>>map(order -> ResponseEntity.ok().body(view(order)))
                .orElseGet(() -> notFound("订单不存在或不可见"));
    }

    public record PayRequest(String correlationId) {
    }

    @PostMapping(path = "/orders/{id}/pay", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> pay(@PathVariable("id") String id,
                                                   @RequestBody PayRequest request) {
        UUID orderId = parse(id);
        if (orderId == null) {
            return badRequest("订单号格式非法");
        }
        if (request == null || request.correlationId() == null) {
            return badRequest("缺少 correlationId");
        }
        var outcome = orders.pay(orderId, request.correlationId());
        return switch (outcome) {
            case TwoStepOrderService.PayOutcome.Paid p ->
                    ResponseEntity.ok().body(Map.of("orderId", p.orderId().toString(),
                            "payStatus", "PAID"));
            case TwoStepOrderService.PayOutcome.Declined d ->
                    ResponseEntity.ok().body(Map.of("orderId", d.orderId().toString(),
                            "payStatus", "NOT_PAY", "paymentResult", "DECLINED"));
            case TwoStepOrderService.PayOutcome.Pending p ->
                    ResponseEntity.ok().body(Map.of("orderId", p.orderId().toString(),
                            "paymentResult", "UNKNOWN"));
            case TwoStepOrderService.PayOutcome.NotFound n -> notFound("订单不存在或不可见");
            case TwoStepOrderService.PayOutcome.Illegal i ->
                    conflict("非法支付目标: " + i.detail());
        };
    }

    public record CancelRequest(String reason, String party) {
    }

    @PostMapping(path = "/orders/{id}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable("id") String id,
                                                      @RequestBody CancelRequest request) {
        UUID orderId = parse(id);
        if (orderId == null) {
            return badRequest("订单号格式非法");
        }
        if (request == null || request.reason() == null || request.reason().isBlank()
                || request.party() == null) {
            return badRequest("缺少 reason/party");
        }
        RefundParty party;
        try {
            party = RefundParty.valueOf(request.party());
        } catch (IllegalArgumentException e) {
            return badRequest("party 必须为 BUYER/SUPPLIER");
        }
        var outcome = orders.cancel(orderId, request.reason(), party);
        return switch (outcome) {
            case TwoStepOrderService.CancelOutcome.Cancelled c ->
                    ResponseEntity.ok().body(Map.of("orderId", c.orderId().toString(),
                            "bookingStatus", "DISCARDED"));
            case TwoStepOrderService.CancelOutcome.RefundCancelled r ->
                    ResponseEntity.ok().body(Map.of("orderId", r.orderId().toString(),
                            "bookingStatus", "DISCARDED", "payStatus", "REFUNDED",
                            "refundId", r.refundId().toString()));
            case TwoStepOrderService.CancelOutcome.NotFound n -> notFound("订单不存在或不可见");
            case TwoStepOrderService.CancelOutcome.Illegal i -> conflict("非法取消: " + i.detail());
        };
    }

    private Map<String, Object> view(TradeOrder order) {
        return Map.of(
                "orderId", order.id().toString(),
                "intentId", order.intentId(),
                "correlationId", order.correlationId(),
                "bookingStatus", order.bookingStatus().name(),
                "payStatus", order.payStatus().name(),
                "sku", order.sku(),
                "quantity", order.quantity(),
                "amount", order.amount().toPlainString());
    }

    private UUID parse(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private ResponseEntity<Map<String, Object>> notFound(String message) {
        return ResponseEntity.status(404).body(Map.of("error", message));
    }

    private ResponseEntity<Map<String, Object>> conflict(String message) {
        return ResponseEntity.status(409).body(Map.of("error", message));
    }
}
