package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.shared.Digests;
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
 * ST-37（M2 方案 §11/L3，回指 §4.4 内容级 digest 巡检 episode 告警模型）。
 * 场景（注入）：review body 被编辑（marker 保留、正文改动）→ 恢复原文 → 再次编辑。
 * 预期断言：episode 语义——首轮漂移告警恰 1 次（同 episode 重复扫描不重发）；恢复原文
 * （实测 digest == 期望）episode 关闭、digest 列置 NULL、恢复期零告警；再次编辑 =
 * 新 episode，再告警恰 1 次。state 全程 PRESENT，不自动改写、不铸 repair 单。
 * 取证：execution_event(PUBLICATION_CONTENT_DRIFTED) 计数 + publication_resource
 * .content_drift_digest/content_drift_detected_at 列 + stub journal 零写。
 */
class St37ContentDriftEpisodeIT extends PostgresITBase {

    private static final String REPO = "objwww/mall";
    private static final int PR = 37;
    private static final long REPOSITORY_ID = 2037L;
    private static final String HEAD = "head" + "a".repeat(36);
    private static final String REVIEW_PROBE_URL =
            "/repos/" + REPO + "/pulls/" + PR + "/reviews?per_page=100&page=1";

    private WireMockServer wiremock;
    private ItHarness harness;
    private String originalBody;

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

    private void stubReviewBody(String body) throws Exception {
        wiremock.stubFor(get(urlEqualTo(REVIEW_PROBE_URL))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(OM.writeValueAsString(List.of(Map.of(
                                "id", 2002, "html_url", "http://x/review/2002", "body", body))))));
    }

    private void makeReviewDue() {
        adminJdbc.sql("UPDATE publication_resource SET next_check_at=now()-interval '1 second' "
                        + "WHERE resource_type='REVIEW'").update();
    }

    private long contentDriftEvents() {
        return adminJdbc.sql("SELECT count(*) FROM execution_event "
                        + "WHERE event_type='PUBLICATION_CONTENT_DRIFTED'")
                .query(Long.class).single();
    }

    @Test
    void editRestoreReeditFollowsEpisodeSemantics() throws Exception {
        // 1) 完整发布一轮；只让 REVIEW 进巡检面（CHECK_RUN 排除，隔离内容漂移路径）
        harness.dispatchOpened(ItHarness.prEvent("st37-d1", REPOSITORY_ID, REPO, PR, HEAD, "opened"),
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
        adminJdbc.sql("UPDATE publication_resource SET next_check_at=now()+interval '1 day' "
                        + "WHERE resource_type='CHECK_RUN'").update();
        UUID reviewOp = adminJdbc.sql(
                        "SELECT operation_id FROM outbox_command WHERE command_type='PUBLISH_REVIEW'")
                .query(UUID.class).single();
        // 期望原文 = PublishReviewHandler.buildBody 产出（零 findings 形态）
        originalBody = "AI Code Review\n\n共 0 个发现：\n\n<!-- ai-review:" + reviewOp + " -->";

        // 2) 对照轮：远端即原文 → 零告警（同时自证本测试构造的原文与生产 buildBody 逐字节一致）
        stubReviewBody(originalBody);
        makeReviewDue();
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(contentDriftEvents()).isZero();
        assertThat(adminJdbc.sql("SELECT count(*) FROM publication_resource "
                        + "WHERE resource_type='REVIEW' AND content_drift_digest IS NULL")
                .query(Long.class).single()).isEqualTo(1);

        // 3) 首轮编辑（episode 1 开启）：告警恰 1 次 + digest 列落实测值
        String edited1 = "AI Code Review\n\n共 0 个发现：\n\n（正文被第三方篡改 v1）\n"
                + "<!-- ai-review:" + reviewOp + " -->";
        stubReviewBody(edited1);
        makeReviewDue();
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(contentDriftEvents()).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT content_drift_digest FROM publication_resource "
                        + "WHERE resource_type='REVIEW'").query(String.class).single())
                .isEqualTo(Digests.sha256Hex(edited1));
        assertThat(adminJdbc.sql("SELECT count(*) FROM publication_resource "
                        + "WHERE resource_type='REVIEW' AND state='PRESENT' "
                        + "AND content_drift_detected_at IS NOT NULL")
                .query(Long.class).single()).isEqualTo(1);

        // 4) 同 episode 重复扫描（digest 未变）：不重复告警
        makeReviewDue();
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(contentDriftEvents()).isEqualTo(1);

        // 5) 恢复原文：episode 关闭（digest/detected_at 置 NULL），恢复期零告警
        stubReviewBody(originalBody);
        makeReviewDue();
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(contentDriftEvents()).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT count(*) FROM publication_resource "
                        + "WHERE resource_type='REVIEW' AND content_drift_digest IS NULL "
                        + "AND content_drift_detected_at IS NULL AND state='PRESENT'")
                .query(Long.class).single()).isEqualTo(1);
        makeReviewDue(); // 恢复期再扫一轮仍零告警
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(contentDriftEvents()).isEqualTo(1);

        // 6) 再次编辑（episode 2）：再告警恰 1 次，digest 列换新值
        String edited2 = "AI Code Review\n\n共 0 个发现：\n\n（正文被第三方篡改 v2）\n"
                + "<!-- ai-review:" + reviewOp + " -->";
        stubReviewBody(edited2);
        makeReviewDue();
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(contentDriftEvents()).isEqualTo(2);
        assertThat(adminJdbc.sql("SELECT content_drift_digest FROM publication_resource "
                        + "WHERE resource_type='REVIEW'").query(String.class).single())
                .isEqualTo(Digests.sha256Hex(edited2));

        // 7) 全程：不自动改写、不铸 repair 单、远端零写（首发各 1 次 POST 后无任何新写）
        assertThat(count("repair_request")).isZero();
        assertThat(adminJdbc.sql("SELECT state FROM publication_resource WHERE resource_type='REVIEW'")
                .query(String.class).single()).isEqualTo("PRESENT");
        wiremock.verify(exactly(1),
                postRequestedFor(urlEqualTo("/repos/" + REPO + "/pulls/" + PR + "/reviews")));
        wiremock.verify(exactly(1), postRequestedFor(urlEqualTo("/repos/" + REPO + "/check-runs")));
    }
}
