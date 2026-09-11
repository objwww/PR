package com.objwww.pr.control.infrastructure.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.control.alert.application.tool.ToolExecutor;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EN-05（§一 logs P0：log_error_aggregate 两级设计第一步——<b>先聚合后读行</b>）：
 * Loki instant metric 查询 {@code sum by (service_name) (count_over_time(...))}，
 * 确定性聚合零 LLM。错误三态同 LogQueryExecutor（EMPTY→NO_DATA /
 * SOURCE_UNAVAILABLE / QUERY_FAILED），service allowlist fail-closed 且越界
 * <b>发出前</b>拒（T03/T05 E 脸；T05 上下文读行 face 归 P1 log_context_around，如实推迟）。
 */
class LokiAggregateExecutorTest {

    private static final WireMockServer WIREMOCK = new WireMockServer(0);
    private static final Set<String> ALLOWLIST = Set.of("control-app", "checkout");
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void start() {
        WIREMOCK.start();
    }

    @AfterAll
    static void stop() {
        WIREMOCK.stop();
    }

    @BeforeEach
    void reset() {
        WIREMOCK.resetAll();
    }

    private LokiAggregateExecutor executor() {
        return new LokiAggregateExecutor(WIREMOCK.baseUrl(), ALLOWLIST,
                HttpClient.newHttpClient());
    }

    private static ToolExecutor.ToolExecution exec(String service) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("since", "2026-09-10T00:00:00Z");
        args.put("until", "2026-09-10T00:05:00Z");
        if (service != null) {
            args.put("service", service);
        }
        return new ToolExecutor.ToolExecution(args,
                System.currentTimeMillis() + 10_000, 65_536);
    }

    @Test
    @DisplayName("聚合契约：sum by (service_name)(count_over_time) 单请求，行={service,count}")
    void aggregateContractCountsByService() throws Exception {
        WIREMOCK.stubFor(get(urlPathEqualTo("/loki/api/v1/query"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"status":"success","data":{"resultType":"vector",
                         "result":[
                          {"metric":{"service_name":"checkout"},"value":[1757059500,"42"]},
                          {"metric":{"service_name":"control-app"},"value":[1757059500,"7"]}]}}
                        """)));

        byte[] body = executor().execute(exec("checkout"));

        Map<?, ?> payload = JSON.readValue(body, Map.class);
        assertThat(payload.get("status")).isEqualTo("success");
        com.fasterxml.jackson.databind.JsonNode rows =
                JSON.readTree(body).path("data").path("result");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).path("service").asText()).isEqualTo("checkout");
        assertThat(rows.get(0).path("count").asLong()).isEqualTo(42L);
        // 聚合表达式钉死：先聚合后读行（逐行读数=超大响应，结构性禁止）
        String query = WIREMOCK.getAllServeEvents().get(0).getRequest().getQueryParams()
                .get("query").firstValue();
        assertThat(query).startsWith("sum by (service_name)")
                .contains("count_over_time")
                .contains("{service_name=\"checkout\"}")
                .contains("[300s]");
        String time = WIREMOCK.getAllServeEvents().get(0).getRequest().getQueryParams()
                .get("time").firstValue();
        assertThat(time).as("time=until 纳秒").isEqualTo("1788998700000000000");
    }

    @Test
    @DisplayName("T06：空聚合 = NO_DATA；500 = SOURCE_UNAVAILABLE；400 良构拒绝 = QUERY_FAILED；429 = RATE_LIMITED")
    void outcomeFacesAreDistinct() {
        WIREMOCK.stubFor(get(urlPathEqualTo("/loki/api/v1/query"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}")));
        assertThatThrownBy(() -> executor().execute(exec(null)))
                .isInstanceOf(ToolModelVisibleException.class)
                .hasMessageContaining("NO_DATA");

        WIREMOCK.resetAll();
        WIREMOCK.stubFor(get(urlPathEqualTo("/loki/api/v1/query"))
                .willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> executor().execute(exec(null)))
                .isInstanceOf(ToolModelVisibleException.class)
                .hasFieldOrPropertyWithValue("reason",
                        ToolModelVisibleReason.SOURCE_UNAVAILABLE);

        WIREMOCK.resetAll();
        WIREMOCK.stubFor(get(urlPathEqualTo("/loki/api/v1/query"))
                .willReturn(aResponse().withStatus(400)));
        assertThatThrownBy(() -> executor().execute(exec(null)))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("QUERY_FAILED");

        WIREMOCK.resetAll();
        WIREMOCK.stubFor(get(urlPathEqualTo("/loki/api/v1/query"))
                .willReturn(aResponse().withStatus(429)));
        assertThatThrownBy(() -> executor().execute(exec(null)))
                .isInstanceOf(ToolModelVisibleException.class)
                .hasFieldOrPropertyWithValue("reason",
                        ToolModelVisibleReason.RATE_LIMITED);
    }

    @Test
    @DisplayName("T03：service 越出 allowlist → 发出前 INVALID_ARGS，零请求")
    void outOfScopeServiceRejectedBeforeAnyRequest() {
        LokiAggregateExecutor executor = executor();
        assertThatThrownBy(() -> executor.execute(exec("payment")))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("allowlist");
        assertThat(WIREMOCK.getAllServeEvents()).as("目标端点计数零").isEmpty();
    }

    @Test
    @DisplayName("窗幅 ≤900s（对齐 logs.query 纪律）；构造空 allowlist fail-closed")
    void windowBoundAndFailClosedConstruction() {
        assertThatThrownBy(() -> executor().execute(exec2(
                "2026-09-10T00:00:00Z", "2026-09-10T00:15:01Z", null)))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("INVALID_ARGS");
        assertThatThrownBy(() -> new LokiAggregateExecutor(
                "http://loki:3100", Set.of(), HttpClient.newHttpClient()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ToolExecutor.ToolExecution exec2(String since, String until,
            String service) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("since", since);
        args.put("until", until);
        if (service != null) {
            args.put("service", service);
        }
        return new ToolExecutor.ToolExecution(args,
                System.currentTimeMillis() + 10_000, 65_536);
    }
}
