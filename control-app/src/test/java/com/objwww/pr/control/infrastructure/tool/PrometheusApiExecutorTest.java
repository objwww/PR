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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EN-05（§一 metrics P0 四工具）执行器契约面（WireMock 真网络假件，T01~T04/T06 E 脸）：
 * prometheus.instant（query_range 的对偶）/ prometheus.catalog（指标名+type+unit）/
 * prometheus.label_values（先发现 label 再拼 PromQL，治乱猜）/ prometheus.rules
 * （RCA 第一跳：阈值/for/runbook_url）。纪律同 PrometheusQueryExecutor：策略/账本归
 * Gateway-Agent，本类只管调用面（有界读、错误两族、service 范围越界在<b>发出前</b>
 * 拒绝——T03 目标端点计数零）。
 */
class PrometheusApiExecutorTest {

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

    private PrometheusApiExecutor api() {
        return new PrometheusApiExecutor(WIREMOCK.baseUrl(), ALLOWLIST,
                HttpClient.newHttpClient());
    }

    private static ToolExecutor.ToolExecution exec(Map<String, Object> args) {
        return new ToolExecutor.ToolExecution(args,
                System.currentTimeMillis() + 10_000, 65_536);
    }

    // ------------------------------------------------------- instant（T01 对偶面）

    @Test
    @DisplayName("instant 契约：GET /api/v1/query?query&time，result 行统一形状回传")
    void instantQueryContract() throws Exception {
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/v1/query"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"status":"success","data":{"resultType":"vector",
                         "result":[{"metric":{"service":"checkout"},"value":[1757059260,"0.42"]}]}}
                        """)));

        byte[] body = api().instantQuery(exec(Map.of(
                "query", "http_server_requests_seconds_count{service=\"checkout\"}",
                "time", "1757059260")));

        assertThat(new String(body, StandardCharsets.UTF_8))
                .contains("\"resultType\":\"vector\"").contains("0.42");
        WIREMOCK.verify(getRequestedFor(urlPathEqualTo("/api/v1/query"))
                .withQueryParam("query", equalTo(
                        "http_server_requests_seconds_count{service=\"checkout\"}"))
                .withQueryParam("time", equalTo("1757059260")));
    }

    @Test
    @DisplayName("instant 空结果：success + 空 result = 模型可见 NO_DATA（T02 不伪造序列）")
    void instantEmptyIsNoData() {
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/v1/query"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}")));

        assertThatThrownBy(() -> api().instantQuery(exec(Map.of(
                "query", "ghost_metric_total", "time", "1757059260"))))
                .isInstanceOf(ToolModelVisibleException.class)
                .hasMessageContaining("NO_DATA");
    }

    @Test
    @DisplayName("instant 503 → 模型可见 REMOTE_UNAVAILABLE；429 → RATE_LIMITED（T06 可区分）")
    void instantFailureFacesAreDistinct() {
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/v1/query"))
                .willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> api().instantQuery(exec(Map.of(
                "query", "up", "time", "1757059260"))))
                .isInstanceOf(ToolModelVisibleException.class)
                .hasFieldOrPropertyWithValue("reason", ToolModelVisibleReason.REMOTE_UNAVAILABLE);

        WIREMOCK.resetAll();
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/v1/query"))
                .willReturn(aResponse().withStatus(429)));
        assertThatThrownBy(() -> api().instantQuery(exec(Map.of(
                "query", "up", "time", "1757059260"))))
                .isInstanceOf(ToolModelVisibleException.class)
                .hasFieldOrPropertyWithValue("reason", ToolModelVisibleReason.RATE_LIMITED);
    }

    // ------------------------------------------------- service 范围越界（T03 前置拒）

    @Test
    @DisplayName("T03：selector 声明越权 service → 发出前 INVALID_ARGS，目标端点计数零")
    void outOfScopeServiceRejectedBeforeAnyRequest() {
        PrometheusApiExecutor api = api();
        assertThatThrownBy(() -> api.instantQuery(exec(Map.of(
                "query", "up{service=\"payment\"}", "time", "1757059260"))))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("service")
                .hasMessageContaining("allowlist");
        assertThatThrownBy(() -> api.labelValues(exec(Map.of(
                "label", "pod", "match", "{service=\"payment\"}"))))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("allowlist");
        assertThat(WIREMOCK.getAllServeEvents()).as("目标端点计数零").isEmpty();
    }

    // ------------------------------------------------------- catalog（T01 第一跳）

    @Test
    @DisplayName("catalog 契约：指标名 + metadata type/unit 合并；match[] 过滤透传")
    void catalogMergesNamesWithMetadata() throws Exception {
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/v1/label/__name__/values"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"status":"success",
                         "data":["http_server_requests_seconds_count","up"]}
                        """)));
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/v1/metadata"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"status":"success","data":{
                          "http_server_requests_seconds_count":
                            {"type":"counter","help":"req","unit":"requests"},
                          "up":{"type":"gauge","help":"up","unit":""}}}
                        """)));

        byte[] body = api().catalogSearch(exec(Map.of(
                "match", "http_server_requests_seconds_count")));

        Map<?, ?> payload = JSON.readValue(body, Map.class);
        assertThat(payload.get("status")).isEqualTo("success");
        List<?> rows = (List<?>) ((Map<?, ?>) payload.get("data")).get("result");
        assertThat(rows).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> http = (Map<String, Object>) rows.stream()
                .filter(r -> ((Map<String, Object>) r).get("name")
                        .equals("http_server_requests_seconds_count"))
                .findFirst().orElseThrow();
        assertThat(http.get("type")).isEqualTo("counter");
        assertThat(http.get("unit")).isEqualTo("requests");
        WIREMOCK.verify(getRequestedFor(urlPathEqualTo("/api/v1/label/__name__/values"))
                .withQueryParam("match", equalTo("http_server_requests_seconds_count")));
    }

    @Test
    @DisplayName("T04：catalog 响应超 resultLimit → 有界流读 RESULT_OVERSIZE（超大响应不进内存）")
    void oversizeCatalogIsBoundedAbort() {
        char[] junk = new char[70_000];
        java.util.Arrays.fill(junk, 'x');
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/v1/label/__name__/values"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"status\":\"success\",\"data\":[\""
                                + new String(junk) + "\"]}")));

        assertThatThrownBy(() -> api().catalogSearch(exec(Map.of())))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("RESULT_OVERSIZE");
    }

    // ------------------------------------------------------------- label_values

    @Test
    @DisplayName("label_values 契约：GET /api/v1/label/{label}/values + match[] selector")
    void labelValuesContract() throws Exception {
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/v1/label/service/values"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"status\":\"success\",\"data\":[\"checkout\",\"control-app\"]}")));

        byte[] body = api().labelValues(exec(Map.of(
                "label", "service", "match", "{job=\"otel-demo\"}")));

        Map<?, ?> payload = JSON.readValue(body, Map.class);
        List<?> rows = (List<?>) ((Map<?, ?>) payload.get("data")).get("result");
        assertThat(rows).hasSize(2);
        WIREMOCK.verify(getRequestedFor(urlPathEqualTo("/api/v1/label/service/values"))
                .withQueryParam("match", equalTo("{job=\"otel-demo\"}")));
    }

    // ----------------------------------------------------------------- rules

    @Test
    @DisplayName("rules 契约：type=alert + alertname 过滤出 expr/for/annotations（RCA 第一跳）")
    void ruleLookupFiltersByAlertname() throws Exception {
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/v1/rules"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"status":"success","data":{"groups":[
                          {"name":"latency","rules":[
                            {"name":"HighErrorRate","query":"rate(errors[5m])>0.5","duration":300,
                             "labels":{"severity":"critical"},
                             "annotations":{"runbook_url":"https://runbook/high-error",
                                            "summary":"err>50%"},
                             "health":"ok","state":"active"},
                            {"name":"OtherRule","query":"up==0","duration":0,
                             "labels":{},"annotations":{},"health":"ok","state":"inactive"}]}]}}
                        """)));

        byte[] body = api().ruleLookup(exec(Map.of("alertname", "HighErrorRate")));

        Map<?, ?> payload = JSON.readValue(body, Map.class);
        List<?> rows = (List<?>) ((Map<?, ?>) payload.get("data")).get("result");
        assertThat(rows).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> rule = (Map<String, Object>) rows.get(0);
        assertThat(rule.get("name")).isEqualTo("HighErrorRate");
        assertThat(String.valueOf(rule.get("query"))).contains("rate(errors[5m])");
        assertThat(rule.get("duration")).isEqualTo(300);
        assertThat(String.valueOf(rule.get("annotations"))).contains("runbook_url");
        WIREMOCK.verify(getRequestedFor(urlPathEqualTo("/api/v1/rules"))
                .withQueryParam("type", equalTo("alert")));
    }

    @Test
    @DisplayName("rules 未命中：如实 NO_DATA（不猜近似规则）")
    void ruleLookupUnknownAlertnameIsNoData() {
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/v1/rules"))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"status\":\"success\",\"data\":{\"groups\":[]}}")));

        assertThatThrownBy(() -> api().ruleLookup(exec(Map.of("alertname", "Ghost"))))
                .isInstanceOf(ToolModelVisibleException.class)
                .hasMessageContaining("NO_DATA");
    }
}
