package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ST-22（M1 方案 §11/L3）Drift 重复扫同一缺失对象：保持 MISSING；漂移事件只一次；
 * 不重发（不插新命令、不改 outbox）。MISSING 行经低频复核排期后仍在巡检扫描集内
 * （§4.6 MISSING 复核），事件去重的守卫在 store 行锁侧。
 */
class ST22DriftRepeatScanIT extends PostgresITBase {

    private static final String HEAD_SHA = "head" + "f".repeat(36);
    private static final String REPO = "objwww/mall";
    private static final int PR = 26;
    private static final String CHECK_PROBE_URL =
            "/repos/" + REPO + "/commits/" + HEAD_SHA + "/check-runs?per_page=100&page=1";
    private static final String REVIEW_PROBE_URL =
            "/repos/" + REPO + "/pulls/" + PR + "/reviews?per_page=100&page=1";
    private static final String SANITY_URL = "/repos/" + REPO;

    private WireMockServer wiremock;
    private ItHarness harness;

    @BeforeEach
    void setUp() {
        wiremock = new WireMockServer(wireMockConfig().dynamicPort());
        wiremock.start();
        harness = new ItHarness(casDir, wiremock.baseUrl());

        // 完整发布一轮：两命令 CONFIRMED + 两资源 PRESENT
        harness.dispatchOpened(ItHarness.prEvent("st22-d1", 2022L, REPO, PR, HEAD_SHA, "opened"),
                ItTarballs.singleFile("src/A.java", "class A {}\n"), "diff");
        harness.modelClient.enqueueContent("[]");
        harness.newWorker("worker-1").runOnce();
        wiremock.stubFor(post(urlEqualTo("/repos/" + REPO + "/check-runs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1001,\"html_url\":\"http://x/check/1001\"}")));
        wiremock.stubFor(post(urlEqualTo("/repos/" + REPO + "/pulls/" + PR + "/reviews"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":2002,\"html_url\":\"http://x/review/2002\"}")));
        harness.newClaimer().runOnce();

        // stub 侧"删除"远端对象：探针列表空；sanity 读可达
        wiremock.stubFor(get(urlEqualTo(CHECK_PROBE_URL))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"check_runs\":[]}")));
        wiremock.stubFor(get(urlEqualTo(REVIEW_PROBE_URL))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[]")));
        wiremock.stubFor(get(urlEqualTo(SANITY_URL))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"full_name\":\"" + REPO + "\"}")));
    }

    @AfterEach
    void tearDown() {
        wiremock.stop();
    }

    private long driftEventCount() {
        return adminJdbc.sql(
                "SELECT count(*) FROM execution_event WHERE event_type = 'PUBLICATION_DRIFT_DETECTED'")
                .query(Long.class).single();
    }

    private List<String> resourceStates() {
        return adminJdbc.sql("SELECT state FROM publication_resource ORDER BY created_at")
                .query(String.class).list();
    }

    @Test
    void repeatScanKeepsMissingAndDoesNotReemit() {
        // 第一轮：确认 MISSING + 事件各一次（同 ST-15 断言口径）
        harness.newDriftReconciler().runOnce();
        assertThat(resourceStates()).containsExactly("MISSING", "MISSING");
        assertThat(driftEventCount()).isEqualTo(2);

        // 低频复核到期（测试动作：拨 next_check_at），再扫两轮
        for (int round = 0; round < 2; round++) {
            adminJdbc.sql("UPDATE publication_resource SET next_check_at = now() - interval '1 second'")
                    .update();
            int processed = harness.newDriftReconciler().runOnce();
            assertThat(processed).isEqualTo(2); // MISSING 复核仍在扫描集内
        }

        // 保持 MISSING；事件仍恰好各一次；零重发
        assertThat(resourceStates()).containsExactly("MISSING", "MISSING");
        assertThat(driftEventCount()).isEqualTo(2);
        assertThat(adminJdbc.sql("SELECT state FROM outbox_command ORDER BY aggregate_sequence")
                .query(String.class).list()).containsExactly("CONFIRMED", "CONFIRMED");
        wiremock.verify(exactly(1),
                postRequestedFor(urlEqualTo("/repos/" + REPO + "/check-runs")));
        wiremock.verify(exactly(1),
                postRequestedFor(urlEqualTo("/repos/" + REPO + "/pulls/" + PR + "/reviews")));
        // 复核真实发生：探针被调用 3 轮 ×2 资源
        wiremock.verify(exactly(3), getRequestedFor(urlEqualTo(CHECK_PROBE_URL)));
        wiremock.verify(exactly(3), getRequestedFor(urlEqualTo(REVIEW_PROBE_URL)));
        wiremock.verify(exactly(6), getRequestedFor(urlPathEqualTo(SANITY_URL)));
    }
}
