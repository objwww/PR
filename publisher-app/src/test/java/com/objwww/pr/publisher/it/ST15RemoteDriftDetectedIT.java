package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.control.domain.model.ReviewRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

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
 * ST-15（M1 方案 §11/L3）远端对象漂移：stub 删 check/review → DriftReconciler 一轮 →
 * 资源标 MISSING + PUBLICATION_DRIFT_DETECTED 恰好一次 + outbox 命令状态不变 + 零重发
 * （stub 写调用计数断言）。sanity 读通过才允许标 MISSING（EX-17 的对照组）。
 */
class ST15RemoteDriftDetectedIT extends PostgresITBase {

    static final String HEAD_SHA = "head" + "e".repeat(36);
    static final String REPO = "objwww/mall";
    static final int PR = 25;
    static final String CHECK_PROBE_URL =
            "/repos/" + REPO + "/commits/" + HEAD_SHA + "/check-runs?per_page=100&page=1";
    static final String REVIEW_PROBE_URL =
            "/repos/" + REPO + "/pulls/" + PR + "/reviews?per_page=100&page=1";
    static final String SANITY_URL = "/repos/" + REPO;

    WireMockServer wiremock;
    ItHarness harness;

    @BeforeEach
    void setUp() {
        wiremock = new WireMockServer(wireMockConfig().dynamicPort());
        wiremock.start();
        harness = new ItHarness(casDir, wiremock.baseUrl());
    }

    @AfterEach
    void tearDown() {
        wiremock.stop();
    }

    /** 完整发布一轮：两命令 CONFIRMED + 两资源 PRESENT；返回 run 与被删前各写调用的初始计数 */
    ReviewRun publishFullLoop(String deliveryId) {
        ReviewRun run = harness.dispatchOpened(
                ItHarness.prEvent(deliveryId, 2015L, REPO, PR, HEAD_SHA, "opened"),
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
        assertThat(adminJdbc.sql("SELECT state FROM outbox_command ORDER BY aggregate_sequence")
                .query(String.class).list()).containsExactly("CONFIRMED", "CONFIRMED");
        assertThat(adminJdbc.sql("SELECT state FROM publication_resource ORDER BY created_at")
                .query(String.class).list()).containsExactly("PRESENT", "PRESENT");
        return run;
    }

    /** stub 侧"删除"远端对象：探针列表返回空；sanity 读（GET repo）可达 */
    void stubRemoteObjectsDeleted() {
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

    long driftEventCount() {
        return adminJdbc.sql(
                "SELECT count(*) FROM execution_event WHERE event_type = 'PUBLICATION_DRIFT_DETECTED'")
                .query(Long.class).single();
    }

    List<String> resourceStates() {
        return adminJdbc.sql("SELECT state FROM publication_resource ORDER BY created_at")
                .query(String.class).list();
    }

    @Test
    void remoteDeleteMarksMissingWithSingleEventAndZeroResend() {
        ReviewRun run = publishFullLoop("st15-d1");
        stubRemoteObjectsDeleted();

        int processed = harness.newDriftReconciler().runOnce();

        // 两资源都巡检到（一轮预算 50 覆盖 2）
        assertThat(processed).isEqualTo(2);
        // 标 MISSING + drift_detected_at 落时间
        assertThat(resourceStates()).containsExactly("MISSING", "MISSING");
        assertThat(adminJdbc.sql(
                "SELECT count(*) FROM publication_resource WHERE drift_detected_at IS NOT NULL")
                .query(Long.class).single()).isEqualTo(2);
        // 每资源恰好一个漂移事件，挂在原 Run 上
        assertThat(driftEventCount()).isEqualTo(2);
        assertThat(harness.eventsOf(run.getId()).stream()
                .filter(e -> e.eventType().name().equals("PUBLICATION_DRIFT_DETECTED")).count())
                .isEqualTo(2);
        // 只检测不修复：outbox 命令状态不变、零重发（写调用计数仍是发布时的各 1 次）
        assertThat(adminJdbc.sql("SELECT state FROM outbox_command ORDER BY aggregate_sequence")
                .query(String.class).list()).containsExactly("CONFIRMED", "CONFIRMED");
        wiremock.verify(exactly(1), postRequestedFor(urlEqualTo("/repos/" + REPO + "/check-runs")));
        wiremock.verify(exactly(1),
                postRequestedFor(urlEqualTo("/repos/" + REPO + "/pulls/" + PR + "/reviews")));
        // 探针与 sanity 读真实发生（读路径计数）
        wiremock.verify(exactly(1), getRequestedFor(urlEqualTo(CHECK_PROBE_URL)));
        wiremock.verify(exactly(1), getRequestedFor(urlEqualTo(REVIEW_PROBE_URL)));
        wiremock.verify(exactly(2), getRequestedFor(urlPathEqualTo(SANITY_URL)));
    }
}
