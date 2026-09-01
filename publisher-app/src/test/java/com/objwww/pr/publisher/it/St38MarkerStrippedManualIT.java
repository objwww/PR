package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ST-38（M2 方案 §11/L3，回指 §4.4"marker 被剥的分类"）。
 * 场景（注入）：review body 的隐藏 marker 被剥除（内容其余逐字节不变）→ 探针按 marker
 * 匹配不到该对象 → NotFound（不走进 content 比对）。
 * 预期断言：走 MISSING 路径（sanity 通过）+ REVIEW 恒 MANUAL → 铸 MANUAL 单停 PENDING；
 * 零自动重发（Planner 不拾取 MANUAL PENDING、stub 零新写）；不产生 CONTENT_DRIFTED
 * （marker 不在 ≡ 对象不可见，不是内容漂移）。
 * 取证：repair_request(tier=MANUAL) + execution_event 计数 + stub journal 零写。
 */
class St38MarkerStrippedManualIT extends PostgresITBase {

    private static final String REPO = "objwww/mall";
    private static final int PR = 38;
    private static final long REPOSITORY_ID = 2038L;
    private static final String HEAD = "head" + "b".repeat(36);
    private static final String REVIEW_PROBE_URL =
            "/repos/" + REPO + "/pulls/" + PR + "/reviews?per_page=100&page=1";

    private WireMockServer wiremock;
    private ItHarness harness;

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

    @Test
    void strippedMarkerGoesMissingManualNeverAutoRepublishes() throws Exception {
        // 1) 完整发布一轮；只让 REVIEW 进巡检面
        harness.dispatchOpened(ItHarness.prEvent("st38-d1", REPOSITORY_ID, REPO, PR, HEAD, "opened"),
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
        adminJdbc.sql("UPDATE publication_resource SET next_check_at=CASE WHEN resource_type='REVIEW' "
                        + "THEN now()-interval '1 second' ELSE now()+interval '1 day' END").update();
        UUID reviewOp = adminJdbc.sql(
                        "SELECT operation_id FROM outbox_command WHERE command_type='PUBLISH_REVIEW'")
                .query(UUID.class).single();

        // 2) marker 被剥（内容其余不变）：原文去掉尾行 <!-- ai-review:{op} -->
        String strippedBody = "AI Code Review\n\n共 0 个发现：\n";
        assertThat(strippedBody).doesNotContain("ai-review:" + reviewOp);
        wiremock.stubFor(get(urlEqualTo(REVIEW_PROBE_URL))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(OM.writeValueAsString(List.of(Map.of(
                                "id", 2002, "html_url", "http://x/review/2002", "body", strippedBody))))));
        wiremock.stubFor(get(urlEqualTo("/repos/" + REPO))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"full_name\":\"" + REPO + "\"}")));

        // 3) 巡检：探针 NotFound（marker 不在）→ sanity 通过 → MISSING + MANUAL 单
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT state FROM publication_resource WHERE resource_type='REVIEW'")
                .query(String.class).single()).isEqualTo("MISSING");
        assertThat(adminJdbc.sql("SELECT resource_type||':'||policy_tier||':'||state FROM repair_request")
                .query(String.class).single()).isEqualTo("REVIEW:MANUAL:PENDING");
        assertThat(adminJdbc.sql("SELECT count(*) FROM execution_event "
                        + "WHERE event_type='PUBLICATION_DRIFT_DETECTED'")
                .query(Long.class).single()).isEqualTo(1);
        // 不产生 CONTENT_DRIFTED：marker 被剥走 NotFound 分类，不走进 content 比对
        assertThat(adminJdbc.sql("SELECT count(*) FROM execution_event "
                        + "WHERE event_type='PUBLICATION_CONTENT_DRIFTED'")
                .query(Long.class).single()).isZero();
        assertThat(adminJdbc.sql("SELECT count(*) FROM publication_resource "
                        + "WHERE resource_type='REVIEW' AND content_drift_digest IS NULL")
                .query(Long.class).single()).isEqualTo(1);

        // 4) 零自动重发：Planner 不拾取 MANUAL PENDING；stub 零新写（首发各 1 次后无新增）
        assertThat(harness.newRepairPlanner().runOnce()).isZero();
        assertThat(count("outbox_command")).isEqualTo(2);
        wiremock.verify(exactly(1),
                postRequestedFor(urlEqualTo("/repos/" + REPO + "/pulls/" + PR + "/reviews")));
        wiremock.verify(exactly(1), postRequestedFor(urlEqualTo("/repos/" + REPO + "/check-runs")));
    }
}
