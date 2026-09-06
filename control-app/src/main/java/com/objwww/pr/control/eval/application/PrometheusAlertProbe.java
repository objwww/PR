package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.GoldenScenarioRegistry;
import com.objwww.pr.shared.Digest;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Prometheus 查询探针（M3-17 默认实现）：按<b>场景注册表的期望 alertname</b> 判定
 * firing/resolved（ALERTS{alertstate=...}，逐码查询）+ 规则表达式摘要（/api/v1/rules）。
 * 只看期望码集合——生产告警的 firing/resolved 不进评测判定面。真栈行为归 195 门；
 * 测试以 {@link AlertProbe} 假件替换。轮询间隔 2s；超时返回 false 不抛中断批量。
 */
public final class PrometheusAlertProbe implements AlertProbe {

    private static final long POLL_INTERVAL_MILLIS = 2_000;

    private final RestClient rest;
    private final String baseUrl;
    private final GoldenScenarioRegistry registry;
    private final Sleeper sleeper;

    public PrometheusAlertProbe(String baseUrl, GoldenScenarioRegistry registry,
                                Sleeper sleeper) {
        this.baseUrl = Objects.requireNonNull(baseUrl);
        this.rest = RestClient.builder().baseUrl(baseUrl).build();
        this.registry = Objects.requireNonNull(registry);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Override
    public boolean awaitAllFiring(String scenarioId, int maxWaitSeconds) {
        return awaitFiringState(scenarioId, true, maxWaitSeconds);
    }

    @Override
    public boolean awaitAllResolved(String scenarioId, int maxWaitSeconds) {
        return awaitFiringState(scenarioId, false, maxWaitSeconds);
    }

    @Override
    public boolean awaitSessionClosed(String scenarioId, int cleanupTimeoutSeconds) {
        // 靶场会话收口由 chaos off CAS + AM2 恢复算法保证；本探针只管告警面。
        return true;
    }

    @Override
    public Digest ruleDigest(String alertname) {
        RulesResponse rules = rest.get()
                .uri(b -> b.path("/api/v1/rules").build())
                .retrieve().body(RulesResponse.class);
        if (rules == null || rules.data() == null || rules.data().groups() == null) {
            return Digest.sha256Of("rule=" + alertname + "|missing");
        }
        return rules.data().groups().stream()
                .filter(g -> g.rules() != null)
                .flatMap(g -> g.rules().stream())
                .filter(r -> alertname.equals(r.name()))
                .findFirst()
                .map(r -> Digest.sha256Of(r.query() == null ? "rule=" + r.name() : r.query()))
                .orElseGet(() -> Digest.sha256Of("rule=" + alertname + "|not_found"));
    }

    /** 目标态：期望码全部 firing（true）或全部非 firing（false），轮询至超时 */
    private boolean awaitFiringState(String scenarioId, boolean expectFiring,
                                     int maxWaitSeconds) {
        List<String> expectedCodes = expectedAlertnames(scenarioId);
        long deadline = System.currentTimeMillis() + maxWaitSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            boolean allMatch = expectedCodes.stream()
                    .allMatch(code -> isFiring(code) == expectFiring);
            if (allMatch) {
                return true;
            }
            try {
                sleeper.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return expectedCodes.stream().allMatch(code -> isFiring(code) == expectFiring);
    }

    private List<String> expectedAlertnames(String scenarioId) {
        return registry.byScenarioId(scenarioId).expectedSymptomCodes();
    }

    private boolean isFiring(String alertname) {
        // PromQL 含 {..}，走 uri(URI) 原样发送——RestClient 的 uri 模板展开会把
        // 标签选择器当占位符（"Not enough variable values available to expand"）
        String query = "ALERTS{alertname=\"" + alertname + "\",alertstate=\"firing\"}";
        URI uri = URI.create(baseUrl + "/api/v1/query?query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8));
        QueryResponse response = rest.get()
                .uri(uri)
                .retrieve().body(QueryResponse.class);
        return response != null && response.data() != null
                && response.data().result() != null
                && !response.data().result().isEmpty();
    }

    private record QueryResponse(Data data) {
        private record Data(List<Result> result) {
            private record Result(Map<String, String> metric) {
            }
        }
    }

    private record RulesResponse(Data data) {
        private record Data(List<Group> groups) {
            private record Group(List<Rule> rules) {
            }

            private record Rule(String name, String query) {
            }
        }
    }
}
