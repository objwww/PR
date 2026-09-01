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
 * EX-14（M1 方案 §11/L4）DriftReconciler 探针 5xx：不标 MISSING + check_error_count 递增 +
 * 连 3 次 → ReconcilerDegraded 告警（措辞修正 #3：探测失败不冒充事实，但必须告警）。
 *
 * <p>review 探针 stub body 与生产 {@code PublishReviewHandler.buildBody} 零 finding 形态
 * 逐字节对齐（EX14 取证盲区修复；经 ExRepairChain.zeroFindingsReviewBody 共享夹具书写）。
 */
class EX14DriftProbe5xxIT extends PostgresITBase {

    private static final String HEAD_SHA = "head" + "0".repeat(36);
    private static final String REPO = "objwww/mall";
    private static final int PR = 27;

    private WireMockServer wiremock;
    private ItHarness harness;
    private UUID checkResourceId;

    @BeforeEach
    void setUp() {
        wiremock = new WireMockServer(wireMockConfig().dynamicPort());
        wiremock.start();
        harness = new ItHarness(casDir, wiremock.baseUrl());

        harness.dispatchOpened(ItHarness.prEvent("ex14-d1", 2014L, REPO, PR, HEAD_SHA, "opened"),
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

        checkResourceId = adminJdbc.sql(
                "SELECT id FROM publication_resource WHERE resource_type = 'CHECK_RUN'")
                .query(UUID.class).single();

        // check 探针持续 500；review 探针 FOUND（对照组：正常资源照常刷新 PRESENT）
        wiremock.stubFor(get(urlEqualTo("/repos/" + REPO + "/commits/" + HEAD_SHA
                        + "/check-runs?per_page=100&page=1"))
                .willReturn(aResponse().withStatus(500)));
        UUID reviewOp = adminJdbc.sql(
                "SELECT operation_id FROM outbox_command WHERE command_type = 'PUBLISH_REVIEW'")
                .query(UUID.class).single();
        wiremock.stubFor(get(urlEqualTo("/repos/" + REPO + "/pulls/" + PR + "/reviews"
                        + "?per_page=100&page=1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(ExRepairChain.reviewListJson(
                                ExRepairChain.zeroFindingsReviewBody(reviewOp)))));
    }

    @AfterEach
    void tearDown() {
        wiremock.stop();
    }

    private int errorCountOf(UUID resourceId) {
        return adminJdbc.sql(
                "SELECT check_error_count FROM publication_resource WHERE id = :id")
                .param("id", resourceId).query(Integer.class).single();
    }

    private String stateOf(UUID resourceId) {
        return adminJdbc.sql("SELECT state FROM publication_resource WHERE id = :id")
                .param("id", resourceId).query(String.class).single();
    }

    @Test
    void probe5xxNeverMarksMissingAndDegradesAtThreshold() {
        for (int round = 1; round <= 3; round++) {
            harness.newDriftReconciler().runOnce();
            assertThat(stateOf(checkResourceId)).isEqualTo("PRESENT"); // 5xx 状态不动
            assertThat(errorCountOf(checkResourceId)).isEqualTo(round);
            // 退避排期后拨回（测试动作），驱动下一轮
            adminJdbc.sql("UPDATE publication_resource SET next_check_at = now() - interval '1 second'"
                    + " WHERE id = :id").param("id", checkResourceId).update();
        }

        // 阈值告警恰好一次（措辞修正 #3）；不标 MISSING、零漂移事件
        assertThat(adminJdbc.sql(
                "SELECT count(*) FROM execution_event WHERE event_type = 'RECONCILER_DEGRADED'")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(adminJdbc.sql(
                "SELECT count(*) FROM execution_event WHERE event_type = 'PUBLICATION_DRIFT_DETECTED'")
                .query(Long.class).single()).isZero();
        assertThat(stateOf(checkResourceId)).isEqualTo("PRESENT");
    }
}
