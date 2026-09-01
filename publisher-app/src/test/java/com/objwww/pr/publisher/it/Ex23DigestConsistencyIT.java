package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.shared.Digests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-23（M2 方案 §11/L4，回指 §4.4 + INC-19）：内容 digest 现算与探针实测算法同源。
 *
 * <p>注入：真实 snapshot/diff 样本走完整发布；模型返回带 diff '+' 前缀污染片段的 finding
 * （走 INC-19 前缀归一化路径）；巡检探针先返回与真实发布<b>逐字节一致</b>的 review body
 * （从 stub 实际收到的 POST 体捕获，非 Handler 代码重建），再返回尾部追加一个空格的编辑版。
 *
 * <p>断言：污染片段经归一化锚定落库（不被丢弃）；一致轮零 CONTENT_DRIFTED 告警（期望端
 * CAS payload 现算 == 实测端探针 body sha256）；编辑轮告警恰好一次，事件 payload 的
 * expected/observed digest 与测试侧独立 sha256 逐值对拍相等；state 全程 PRESENT（只告警
 * 不改写，R2）；零 repair_request。
 *
 * <p>取证：review_finding 行号、execution_event(PUBLICATION_CONTENT_DRIFTED) payload 双 digest、
 * publication_resource.content_drift_digest、stub 请求体。
 *
 * <p>复原：每方法 TRUNCATE 全表（基座）+ 独立 WireMock 实例。
 */
class Ex23DigestConsistencyIT extends PostgresITBase {

    private static final String HEAD = "head" + "8".repeat(36);
    private static final String REPO = "objwww/mall";
    private static final int PR = 23;
    private static final long REPO_ID = 2023L;
    private static final String FILE_CONTENT =
            "package a;\n\npublic class A {\n    void f() {\n        int x = 1;\n    }\n}\n";
    // 行号锚点：第 5 行 "int x = 1;"（模型报 99 + 片段带 diff '+' 前缀，双重失真由工程侧纠正）
    private static final String CHECK_PROBE =
            "/repos/" + REPO + "/commits/" + HEAD + "/check-runs?per_page=100&page=1";
    private static final String REVIEW_PROBE =
            "/repos/" + REPO + "/pulls/" + PR + "/reviews?per_page=100&page=1";

    private WireMockServer wiremock;
    private ItHarness harness;
    private UUID reviewOp;
    private String originalBody;

    @BeforeEach
    void setUp() throws Exception {
        wiremock = new WireMockServer(wireMockConfig().dynamicPort());
        wiremock.start();
        harness = new ItHarness(casDir, wiremock.baseUrl());

        harness.dispatchOpened(ItHarness.prEvent("ex23-d1", REPO_ID, REPO, PR, HEAD, "opened"),
                ItTarballs.singleFile("src/A.java", FILE_CONTENT), "diff");
        harness.modelClient.enqueueContent("""
                [
                  {"file":"src/A.java","line":99,"existing_code":"+int x = 1;",
                   "rule":"magic-number","severity":"MINOR","message":"魔法数字：建议命名常量"}
                ]
                """);
        harness.newWorker("worker-1").runOnce();

        // INC-19 前缀归一化生效：污染片段未丢弃，锚定到真实第 5 行
        assertThat(adminJdbc.sql("SELECT line_start FROM review_finding").query(Integer.class).list())
                .containsExactly(5);

        UUID checkOp = adminJdbc.sql(
                "SELECT operation_id FROM outbox_command WHERE command_type = 'CREATE_CHECK'")
                .query(UUID.class).single();
        reviewOp = adminJdbc.sql(
                "SELECT operation_id FROM outbox_command WHERE command_type = 'PUBLISH_REVIEW'")
                .query(UUID.class).single();

        wiremock.stubFor(post(urlEqualTo("/repos/" + REPO + "/check-runs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1001,\"html_url\":\"http://x/check/1001\"}")));
        wiremock.stubFor(post(urlEqualTo("/repos/" + REPO + "/pulls/" + PR + "/reviews"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":2002,\"html_url\":\"http://x/review/2002\"}")));
        harness.newClaimer().runOnce();

        // 期望端原料 = stub 侧真实收到的 body（即"远端实际存储内容"的事实源，不经 Handler 重建）
        var posts = wiremock.findAll(postRequestedFor(
                urlEqualTo("/repos/" + REPO + "/pulls/" + PR + "/reviews")));
        assertThat(posts).hasSize(1);
        originalBody = OM.readTree(posts.get(0).getBodyAsString()).get("body").asText();
        assertThat(originalBody).contains("<!-- ai-review:" + reviewOp + " -->");
        assertThat(originalBody).contains("魔法数字"); // 归一化后的 finding 进入正文

        // 探针 FOUND：check 按 external_id、review 按 marker 命中且 body 与远端逐字节一致
        wiremock.stubFor(get(urlEqualTo(CHECK_PROBE))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"check_runs\":[{\"id\":1001,\"external_id\":\"" + checkOp
                                + "\",\"html_url\":\"http://x/check/1001\"}]}")));
        stubReviewProbe(originalBody);
    }

    @AfterEach
    void tearDown() {
        wiremock.stop();
    }

    @Test
    void expectedAndObservedDigestShareSameAlgorithm() {
        // 一致轮：期望端（CAS payload 现算 buildBody→sha256）与实测端（探针 body sha256）同源 → 零告警
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(2);
        assertThat(contentDriftEvents()).isZero();
        assertThat(reviewRow().get("state")).isEqualTo("PRESENT");
        assertThat(reviewRow().get("drift")).isNull();
        assertThat(count("repair_request")).isZero();

        // 编辑轮：尾部追加一个空格（逐字节敏感语义）；事件双 digest 对拍测试侧独立 sha256
        String editedBody = originalBody + " ";
        stubReviewProbe(editedBody);
        ExRepairChain.expediteChecks("REVIEW");
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);

        assertThat(contentDriftEvents()).isEqualTo(1);
        Map<String, String> event = adminJdbc.sql("""
                SELECT payload ->> 'expected_digest' AS exp, payload ->> 'observed_digest' AS obs
                  FROM execution_event WHERE event_type = 'PUBLICATION_CONTENT_DRIFTED'
                """).query((rs, n) -> Map.of("exp", rs.getString("exp"), "obs", rs.getString("obs")))
                .single();
        assertThat(event)
                .containsEntry("exp", Digests.sha256Hex(originalBody))
                .containsEntry("obs", Digests.sha256Hex(editedBody));
        assertThat(reviewRow().get("drift")).isEqualTo(Digests.sha256Hex(editedBody));
        assertThat(reviewRow().get("state")).isEqualTo("PRESENT");
        assertThat(count("repair_request")).isZero(); // 内容漂移只告警，不铸修复单
    }

    private void stubReviewProbe(String body) {
        wiremock.stubFor(get(urlEqualTo(REVIEW_PROBE))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(ExRepairChain.reviewListJson(body))));
    }

    private long contentDriftEvents() {
        return adminJdbc.sql(
                "SELECT count(*) FROM execution_event WHERE event_type = 'PUBLICATION_CONTENT_DRIFTED'")
                .query(Long.class).single();
    }

    private Map<String, Object> reviewRow() {
        return adminJdbc.sql(
                "SELECT state, content_drift_digest FROM publication_resource"
                        + " WHERE resource_type = 'REVIEW'")
                .query((rs, n) -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("state", rs.getString("state"));
                    row.put("drift", rs.getString("content_drift_digest"));
                    return row;
                }).single();
    }
}
