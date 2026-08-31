package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-17（M1 方案 §11/L4 + E2E-18/F-3）探针 404 但 sanity 读失败（权限撤销场景：
 * GitHub 以 404 替代 403 隐藏私有资源，无法区分"不存在"与"无权限"）——
 * 不标 MISSING，标 UNKNOWN + 权限告警事件；权限异常绝不冒充"不存在"。
 */
class EX17DriftSanityFailIT extends PostgresITBase {

    private static final String HEAD_SHA = "head" + "1".repeat(36);
    private static final String REPO = "objwww/mall";
    private static final int PR = 28;

    private WireMockServer wiremock;
    private ItHarness harness;

    @BeforeEach
    void setUp() {
        wiremock = new WireMockServer(wireMockConfig().dynamicPort());
        wiremock.start();
        harness = new ItHarness(casDir, wiremock.baseUrl());

        harness.dispatchOpened(ItHarness.prEvent("ex17-d1", 2017L, REPO, PR, HEAD_SHA, "opened"),
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

        // check 探针：列表空（= 对象 404 语义）；sanity 读 404（权限撤销不可区分）
        wiremock.stubFor(get(urlEqualTo("/repos/" + REPO + "/commits/" + HEAD_SHA
                        + "/check-runs?per_page=100&page=1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"check_runs\":[]}")));
        wiremock.stubFor(get(urlEqualTo("/repos/" + REPO))
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Not Found\"}")));
        // review 探针 FOUND（对照组：同轮其他资源不受影响）
        UUID reviewOp = adminJdbc.sql(
                "SELECT operation_id FROM outbox_command WHERE command_type = 'PUBLISH_REVIEW'")
                .query(UUID.class).single();
        wiremock.stubFor(get(urlEqualTo("/repos/" + REPO + "/pulls/" + PR + "/reviews"
                        + "?per_page=100&page=1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":2002,\"html_url\":\"http://x/review/2002\","
                                + "\"body\":\"AI Code Review\\n<!-- ai-review:" + reviewOp
                                + " -->\"}]")));
    }

    @AfterEach
    void tearDown() {
        wiremock.stop();
    }

    @Test
    void sanityFailureMarksUnknownNotMissing() {
        harness.newDriftReconciler().runOnce();

        // check 资源：UNKNOWN（不是 MISSING！）；review 资源：PRESENT 照常
        assertThat(adminJdbc.sql(
                "SELECT state FROM publication_resource WHERE resource_type = 'CHECK_RUN'")
                .query(String.class).single()).isEqualTo("UNKNOWN");
        assertThat(adminJdbc.sql(
                "SELECT state FROM publication_resource WHERE resource_type = 'REVIEW'")
                .query(String.class).single()).isEqualTo("PRESENT");
        // 权限告警落账；漂移事件零（权限异常不冒充"不存在"）
        assertThat(adminJdbc.sql(
                "SELECT count(*) FROM execution_event"
                        + " WHERE event_type = 'PUBLICATION_DRIFT_PERMISSION_ALERT'")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(adminJdbc.sql(
                "SELECT count(*) FROM execution_event WHERE event_type = 'PUBLICATION_DRIFT_DETECTED'")
                .query(Long.class).single()).isZero();
        // UNKNOWN 不在巡检扫描集：再扫一轮零处理
        assertThat(harness.newDriftReconciler().runOnce()).isZero();
    }
}
