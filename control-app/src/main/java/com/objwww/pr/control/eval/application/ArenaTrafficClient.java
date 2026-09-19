package com.objwww.pr.control.eval.application;

import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 靶场评测流量客户端（M3-30 部署门补齐）：F1~F3 注入需要 chaos 前缀的订单流量才会
 * 产生期望症状（Gauge 面由靶场流量驱动）——注册表只登记注入参数，流量recipes 归
 * {@link ArenaChaosScenarioDriver} 按 chaos_family 执行，本接口只是最小传输面。
 * 命名与 AM2 E2E 驱动一致（intent chaos- 前缀 + correlationId = target 后缀），
 * live 流量零污染（INV-AM2-1）。
 *
 * <p>BA-178：{@link #createOrder} 返回订单号（F9+ 配方需要拿单号走支付腿；
 * F16 入口静默等"受理但无单"形态返回 null——无单号可付，配方如实跳过支付腿），
 * 并新增 {@link #payOrder} 支付腿传输面（F9 掉单配方：创单+支付，capture 成功
 * 事实落库后由故障点吞掉收口）。
 */
public interface ArenaTrafficClient {

    /** 创单（非 2xx 抛出，由 runner 记 activate_failed 落档，不吞错）；
     *  返回订单号；受理但无单形态（如 F16 入口静默）返回 null */
    String createOrder(String intentId, String correlationId, String sku);

    /** 支付（非 2xx 抛出同创单纪律）；orderId 来自 {@link #createOrder} 返回 */
    void payOrder(String orderId, String correlationId);

    /** 默认 HTTP 实现（order-arena 业务 API，经 alert-net 私网） */
    final class Http implements ArenaTrafficClient {

        private final RestClient rest;

        public Http(String arenaBaseUrl) {
            this.rest = RestClient.builder().baseUrl(
                    Objects.requireNonNull(arenaBaseUrl)).build();
        }

        @Override
        public String createOrder(String intentId, String correlationId, String sku) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("intentId", intentId);
            body.put("correlationId", correlationId);
            body.put("buyerId", "chaos-eval");
            body.put("sku", sku);
            body.put("quantity", 1);
            body.put("amount", "10.00");
            Map<?, ?> resp = rest.post().uri("/orders").body(body).retrieve()
                    .body(Map.class);
            Object orderId = resp == null ? null : resp.get("orderId");
            return orderId == null ? null : orderId.toString();
        }

        @Override
        public void payOrder(String orderId, String correlationId) {
            rest.post().uri("/orders/{id}/pay", orderId)
                    .body(Map.of("correlationId", correlationId))
                    .retrieve().toBodilessEntity();
        }
    }
}
