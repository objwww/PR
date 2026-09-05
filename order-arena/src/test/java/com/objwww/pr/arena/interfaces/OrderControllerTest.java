package com.objwww.pr.arena.interfaces;

import com.objwww.pr.arena.application.TwoStepOrderService;
import com.objwww.pr.arena.domain.model.BookingStatus;
import com.objwww.pr.arena.domain.model.PayStatus;
import com.objwww.pr.arena.domain.model.TradeOrder;
import com.objwww.pr.arena.domain.repository.TradeOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2-14 正常业务 API 契约（standalone MockMvc）：正常链 201/200；CREATED 查询 404；
 * 非法状态迁移 409；幂等处理中 202+Retry-After；同 intent 不同 digest 409；参数非法 400。
 * TwoStepOrderService 以可编程假实现替身（真链路归 195 的 IT/E2E）。
 */
class OrderControllerTest {

    private MockMvc mvc;
    private FakeOrderService service;
    private FakeTradeOrders trades;

    @BeforeEach
    void setUp() {
        service = new FakeOrderService();
        trades = new FakeTradeOrders();
        mvc = MockMvcBuilders.standaloneSetup(
                        new OrderController(service, trades, 2))
                .build();
    }

    @Test
    void createHappyPathReturns201Enabled() throws Exception {
        UUID orderId = UUID.randomUUID();
        service.createOutcome = new TwoStepOrderService.CreateOutcome.Created(orderId);
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"intentId":"i-1","correlationId":"live-a","buyerId":"b1",
                                 "sku":"sku-1","quantity":1,"amount":10.00}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingStatus").value("ENABLED"))
                .andExpect(jsonPath("$.orderId").value(orderId.toString()));
    }

    @Test
    void createdInvisibleToQuery() throws Exception {
        UUID id = UUID.randomUUID();
        trades.visible = Optional.empty();
        mvc.perform(get("/orders/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void idempotencyProcessingReturns202WithRetryAfter() throws Exception {
        service.createOutcome = new TwoStepOrderService.CreateOutcome.Processing();
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"intentId":"i-2","correlationId":"live-a","buyerId":"b1",
                                 "sku":"sku-1","quantity":1,"amount":10.00}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(mvc2 -> assertThat(
                        mvc2.getResponse().getHeader("Retry-After")).isEqualTo("2"));
    }

    @Test
    void intentConflictReturns409() throws Exception {
        service.createOutcome = new TwoStepOrderService.CreateOutcome.Conflict();
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"intentId":"i-3","correlationId":"live-a","buyerId":"b1",
                                 "sku":"sku-1","quantity":1,"amount":10.00}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void payIllegalStateReturns409() throws Exception {
        service.payOutcome = new TwoStepOrderService.PayOutcome.Illegal(UUID.randomUUID(),
                "DISCARDED/NOT_PAY");
        mvc.perform(post("/orders/" + UUID.randomUUID() + "/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correlationId\":\"live-a\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void payUnknownMapsToPending() throws Exception {
        UUID id = UUID.randomUUID();
        service.payOutcome = new TwoStepOrderService.PayOutcome.Pending(id);
        mvc.perform(post("/orders/" + id + "/pay").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correlationId\":\"chaos-f3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentResult").value("UNKNOWN"));
    }

    @Test
    void cancelRefundPathReturnsRefunded() throws Exception {
        UUID id = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        service.cancelOutcome = new TwoStepOrderService.CancelOutcome.RefundCancelled(id, refundId);
        mvc.perform(post("/orders/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"changed mind\",\"party\":\"BUYER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payStatus").value("REFUNDED"))
                .andExpect(jsonPath("$.refundId").value(refundId.toString()));
    }

    @Test
    void malformedRequestsReturn400() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intentId\":\"\",\"correlationId\":\"nope\",\"buyerId\":null}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/orders/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(post("/orders/" + UUID.randomUUID() + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\",\"party\":\"GHOST\"}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ 假实现

    private static final class FakeOrderService extends TwoStepOrderService {
        CreateOutcome createOutcome;
        PayOutcome payOutcome;
        CancelOutcome cancelOutcome;

        FakeOrderService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public CreateOutcome create(String intentId, String correlationId, String buyerId,
                                    String sku, int quantity, BigDecimal amount) {
            return createOutcome;
        }

        @Override
        public PayOutcome pay(UUID orderId, String correlationId) {
            return payOutcome;
        }

        @Override
        public CancelOutcome cancel(UUID orderId, String reason,
                                    com.objwww.pr.arena.domain.model.RefundParty party) {
            return cancelOutcome;
        }
    }

    private static final class FakeTradeOrders implements TradeOrderRepository {
        Optional<TradeOrder> visible = Optional.empty();

        @Override
        public com.objwww.pr.arena.domain.model.OrderSnapshot insertCreatedSnapshot(
                TradeOrder tradeOrder) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<TradeOrder> findById(UUID id) {
            return Optional.empty();
        }

        @Override
        public Optional<TradeOrder> findVisibleById(UUID id) {
            return visible;
        }

        @Override
        public boolean casBookingStatus(UUID id, BookingStatus from, BookingStatus to,
                                        String reason) {
            return false;
        }

        @Override
        public boolean casPayStatus(UUID id, PayStatus from, PayStatus to) {
            return false;
        }

        @Override
        public java.util.List<TradeOrder> findByIntentId(String intentId) {
            return java.util.List.of();
        }
    }
}
