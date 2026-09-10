package com.objwww.pr.control.infrastructure.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.control.alert.application.tool.ToolExecutor;
import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.application.agent.MetricsAgent;
import com.objwww.pr.control.alert.application.agent.SingleToolEvidenceAgent.AgentOutcome;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolPolicy;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import com.objwww.pr.control.alert.domain.tool.ToolRisk;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prometheus 契约真网络面（AM4 M4-27 任务行：WireMock/Prometheus 契约）：
 * GET /api/v1/query_range 参数契约（query/start/end/step）× 固定 replay fixture 响应 →
 * MetricsAgent 经真实 ToolGateway + PrometheusQueryExecutor 全链产证据。
 * 数据源限制（评审 P0-7 同纪律）：本套件只证明契约形状，不宣称 Live 数据。
 */
class PrometheusMetricsWireMockTest {

    private static final WireMockServer WIREMOCK = new WireMockServer(0);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterAll
    static void stop() {
        WIREMOCK.stop();
    }

    private static String base() {
        if (!WIREMOCK.isRunning()) {
            WIREMOCK.start();
        }
        return WIREMOCK.baseUrl();
    }

    @Test
    void queryRangeContractProducesEvidenceEndToEnd() throws Exception {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/v1/query_range"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(fixtureBytes())));
        MemEvidence evidence = new MemEvidence();
        MetricsAgent agent = agent(base(), evidence);

        MetricsAgent.AgentResult result = agent.investigate(context(), query());

        assertThat(result.outcome()).isEqualTo(AgentOutcome.EVIDENCE_PRODUCED);
        WIREMOCK.verify(getRequestedFor(urlPathEqualTo("/api/v1/query_range"))
                .withQueryParam("query", equalTo("cpu_usage_percent"))
                .withQueryParam("start", equalTo("1757059200"))
                .withQueryParam("end", equalTo("1757059260"))
                .withQueryParam("step", equalTo("30s")));
        EvidenceEnvelope stored = EvidenceEnvelope.verify(evidence.rows.get(0));
        Map<String, Object> payload = MAPPER.readValue(stored.canonicalPayload(), Map.class);
        assertThat(payload.get("status")).isEqualTo("success");
        Map<?, ?> data = (Map<?, ?>) payload.get("data");
        assertThat((List<?>) data.get("result")).hasSize(2);
        assertThat(evidence.ledger.rows.get(0).state()).isEqualTo(ToolInvocationState.SUCCESS);
    }

    @Test
    void prometheus5xxIsModelVisibleRetryableFailure() {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/v1/query_range"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));
        MemEvidence evidence = new MemEvidence();
        MetricsAgent agent = agent(base(), evidence);

        MetricsAgent.AgentResult result = agent.investigate(context(), query());

        assertThat(result.outcome()).isEqualTo(AgentOutcome.FAILED);
        assertThat(result.errorClass()).isEqualTo("REMOTE_UNAVAILABLE");
        assertThat(evidence.rows).isEmpty();
        assertThat(evidence.ledger.rows.get(0).state()).isEqualTo(ToolInvocationState.FAILED);
        assertThat(evidence.ledger.rows.get(0).reason()).isEqualTo(ToolReasonCode.TRANSPORT_UNKNOWN);
    }

    @Test
    void prometheus429IsRateLimited() {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/v1/query_range"))
                .willReturn(aResponse().withStatus(429)));
        MemEvidence evidence = new MemEvidence();
        MetricsAgent agent = agent(base(), evidence);

        MetricsAgent.AgentResult result = agent.investigate(context(), query());

        assertThat(result.outcome()).isEqualTo(AgentOutcome.FAILED);
        assertThat(result.errorClass()).isEqualTo("RATE_LIMITED");
        assertThat(evidence.ledger.rows.get(0).reason()).isEqualTo(ToolReasonCode.RATE_LIMITED);
    }

    @Test
    void oversizedResponseIsAboundedAtLimitPlusOneNotFullyBuffered() {
        // EX-A4a（F17）有界流读：limit=256，响应 10KB——executor 读到 257 字节即断
        // （控制面 RESULT_OVERSIZE），不把 10KB 全量搬进内存
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/v1/query_range"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("x".repeat(10 * 1_024))));
        MemEvidence evidence = new MemEvidence();
        PrometheusQueryExecutor executor = new PrometheusQueryExecutor(base());
        ToolRegistry registry = new ToolRegistry(List.of(new ToolRegistry.Registration(
                smallLimitDefinition(256), executor)));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors
                .newFixedThreadPool(1);
        ToolGateway gateway = new ToolGateway(registry,
                new ToolPolicy(Set.of("prometheus.query")), pool,
                java.time.Clock.systemUTC(), null);
        try {
            assertThatThrownBy(() -> gateway.invoke(gatewayInvocation(query())))
                    .isInstanceOfSatisfying(ToolControlPlaneException.class,
                            e -> assertThat(e.reason())
                                    .isEqualTo(ToolControlReason.RESULT_OVERSIZE));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void windowSpanAndStepSemanticsAreValidatedBeforeHttp() {
        // EX-A4a（F17）语义约束：窗幅 ≤3600s、step ≤60s——executor 域内校验先于触网
        PrometheusQueryExecutor executor =
                new PrometheusQueryExecutor("http://prometheus-invalid:9090");
        // 窗幅超限（4800s）
        assertThatThrownBy(() -> executor.execute(new ToolExecutor.ToolExecution(
                Map.of("query", "up", "start", "1757059200", "end", "1757064000",
                        "step", "30s"),
                System.currentTimeMillis() + 5_000, 65_536)))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("窗幅");
        // step 超限（2m=120s > 60s）
        assertThatThrownBy(() -> executor.execute(new ToolExecutor.ToolExecution(
                Map.of("query", "up", "start", "1757059200", "end", "1757059260",
                        "step", "2m"),
                System.currentTimeMillis() + 5_000, 65_536)))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("step");
        // 倒挂窗同样拒（end-start < 0）
        assertThatThrownBy(() -> executor.execute(new ToolExecutor.ToolExecution(
                Map.of("query", "up", "start", "1757059260", "end", "1757059200",
                        "step", "30s"),
                System.currentTimeMillis() + 5_000, 65_536)))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("窗幅");
    }

    /** limit 收紧的 prometheus 定义（schema 同形，仅 resultLimitBytes 变小） */
    private static ToolDefinition smallLimitDefinition(long limitBytes) {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("query", Map.of("type", "string"));
        properties.put("start", Map.of("type", "string"));
        properties.put("end", Map.of("type", "string"));
        properties.put("step", Map.of("type", "string"));
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query", "start", "end", "step"));
        return new ToolDefinition("prometheus.query", "1", schema, ToolRisk.R0, 5000,
                limitBytes);
    }

    private static ToolGateway.ToolInvocation gatewayInvocation(
            MetricsAgent.MetricsQuery query) {
        return new ToolGateway.ToolInvocation(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, MetricsAgent.TOOL_NAME, MetricsAgent.TOOL_VERSION,
                "2026-09-05T07:50:00Z/2026-09-05T08:00:00Z", MetricsAgent.argsOf(query),
                null);
    }

    // ------------------------------------------------------------------ 夹具

    private static MetricsAgent agent(String baseUrl, MemEvidence evidence) {
        PrometheusQueryExecutor executor = new PrometheusQueryExecutor(baseUrl);
        ToolRegistry registry = new ToolRegistry(List.of(new ToolRegistry.Registration(
                definition(), executor)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ToolGateway gateway = new ToolGateway(registry,
                new ToolPolicy(Set.of("prometheus.query")), pool,
                java.time.Clock.systemUTC(), null);
        AgentProfile profile = new AgentProfile("metrics", "1", "prompt-metrics", "pv",
                Set.of("prometheus.query"), Map.of(), Map.of("type", "object"));
        return new MetricsAgent(profile, registry, gateway, evidence, evidence.ledger, MAPPER);
    }

    private static ToolDefinition definition() {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("query", Map.of("type", "string"));
        properties.put("start", Map.of("type", "string"));
        properties.put("end", Map.of("type", "string"));
        properties.put("step", Map.of("type", "string"));
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query", "start", "end", "step"));
        return new ToolDefinition("prometheus.query", "1", schema, ToolRisk.R0, 5000, 65536);
    }

    private static MetricsAgent.CallContext context() {
        return new MetricsAgent.CallContext(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, 0L, null,
                "2026-09-05T07:50:00Z/2026-09-05T08:00:00Z");
    }

    private static MetricsAgent.MetricsQuery query() {
        return new MetricsAgent.MetricsQuery("cpu_usage_percent",
                "1757059200", "1757059260", "30s");
    }

    private static byte[] fixtureBytes() {
        try (InputStream in = PrometheusMetricsWireMockTest.class
                .getResourceAsStream("/fixtures/prometheus/query-range-f1-cpu.json")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 证据 + 账本内存件（真网络契约面只关注落库语义） */
    static final class MemEvidence implements EvidenceRepository {
        final List<EvidenceEnvelope> rows = new ArrayList<>();
        final MemLedger ledger = new MemLedger();

        @Override
        public void insert(EvidenceEnvelope envelope) {
            rows.add(envelope);
        }

        @Override
        public Optional<EvidenceEnvelope> findById(UUID id) {
            return rows.stream().filter(e -> e.evidenceId().equals(id)).findFirst();
        }

        @Override
        public List<EvidenceEnvelope> findByRunId(UUID runId) {
            return rows.stream().filter(e -> e.runId().equals(runId)).toList();
        }
    }

    static final class MemLedger implements RcaToolInvocationLedger {
        record Row(UUID operationId, String toolName, ToolInvocationState state,
                ToolReasonCode reason, String actionDigest) {
        }

        final List<Row> rows = new ArrayList<>();

        @Override
        public void open(InvocationIdentity identity) {
            rows.add(new Row(identity.operationId(), identity.toolName(),
                    ToolInvocationState.PENDING, null, identity.actionDigest()));
        }

        @Override
        public boolean succeed(UUID operationId) {
            return settle(operationId, ToolInvocationState.SUCCESS, null);
        }

        @Override
        public boolean fail(UUID operationId, ToolInvocationState terminal,
                ToolReasonCode reasonCode) {
            return settle(operationId, terminal, reasonCode);
        }

        private boolean settle(UUID id, ToolInvocationState state, ToolReasonCode reason) {
            for (int k = 0; k < rows.size(); k++) {
                Row row = rows.get(k);
                if (row.operationId().equals(id) && row.state() == ToolInvocationState.PENDING) {
                    rows.set(k, new Row(id, row.toolName(), state, reason, row.actionDigest()));
                    return true;
                }
            }
            return false;
        }
    }
}
