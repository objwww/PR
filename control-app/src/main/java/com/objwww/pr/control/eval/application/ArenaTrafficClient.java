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
 */
public interface ArenaTrafficClient {

    /** 创单（非 2xx 抛出，由 runner 记 activate_failed 落档，不吞错） */
    void createOrder(String intentId, String correlationId, String sku);

    /** 默认 HTTP 实现（order-arena 业务 API，经 alert-net 私网） */
    final class Http implements ArenaTrafficClient {

        private final RestClient rest;

        public Http(String arenaBaseUrl) {
            this.rest = RestClient.builder().baseUrl(
                    Objects.requireNonNull(arenaBaseUrl)).build();
        }

        @Override
        public void createOrder(String intentId, String correlationId, String sku) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("intentId", intentId);
            body.put("correlationId", correlationId);
            body.put("buyerId", "chaos-eval");
            body.put("sku", sku);
            body.put("quantity", 1);
            body.put("amount", "10.00");
            rest.post().uri("/orders").body(body).retrieve().toBodilessEntity();
        }
    }
}
