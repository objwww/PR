package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.control.domain.model.ReviewRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ST-32（M2 方案 §11/L3，回指 I22/§4.3 世代 gate）。
 * 场景（注入）：CHECK_RUN 漂移检出（repair PENDING/AUTO）后 PR 换届——push 新 commit
 * （synchronize，subject 当前 revision 换世代），RepairPlanner 才扫描该单。
 * 预期断言：request → EXPIRED + REPAIR_EXPIRED 事件（reason=STALE_GENERATION）；
 * 绝不向旧世代补写——不建 REPAIR Run、不铸 repair 命令、stub 零远端写。
 * 取证：repair_request 全列 + execution_event(REPAIR_EXPIRED) + stub journal 零写。
 */
class St32RepairEpochExpiredIT extends PostgresITBase {

    private static final String REPO = "objwww/mall";
    private static final int PR = 32;
    private static final long REPOSITORY_ID = 2032L;
    private static final String HEAD1 = "head" + "4".repeat(36);
    private static final String HEAD2 = "head" + "5".repeat(36);

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
    void pushAfterDetectionExpiresRepairWithoutRemoteWrite() {
        // 1) 完整发布一轮（CREATE_CHECK + PUBLISH_REVIEW 均 CONFIRMED）
        harness.dispatchOpened(ItHarness.prEvent("st32-d1", REPOSITORY_ID, REPO, PR, HEAD1, "opened"),
                ItTarballs.singleFile("src/A.java", "class A {}\n"), "diff");
        harness.modelClient.enqueueContent("[]");
        harness.newWorker("worker-1").runOnce();
        wiremock.stubFor(post(urlEqualTo("/repos/" + REPO + "/check-runs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1001,\"html_url\":\"http://x/check/1001\"}")));
        wiremock.stubFor(post(urlEqualTo("/repos/" + REPO + "/pulls/" + PR + "/reviews"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":2001,\"html_url\":\"http://x/review/2001\"}")));
        harness.newClaimer().runOnce();

        // 2) 漂移检出：只让 CHECK_RUN 到期；远端被删（探针空）+ sanity 通过 → AUTO 单 PENDING
        adminJdbc.sql("UPDATE publication_resource SET next_check_at=CASE WHEN resource_type='CHECK_RUN' "
                        + "THEN now()-interval '1 second' ELSE now()+interval '1 day' END").update();
        wiremock.stubFor(get(urlEqualTo("/repos/" + REPO + "/commits/" + HEAD1
                        + "/check-runs?per_page=100&page=1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"check_runs\":[]}")));
        wiremock.stubFor(get(urlEqualTo("/repos/" + REPO))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"full_name\":\"" + REPO + "\"}")));
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT policy_tier||':'||state FROM repair_request")
                .query(String.class).single()).isEqualTo("AUTO:PENDING");

        // 3) PR 换届：push 新 commit（新 revision 成为当前世代；不跑 worker = 新世代尚无命令）
        ReviewRun run2 = harness.dispatchOpened(
                ItHarness.prEvent("st32-d2", REPOSITORY_ID, REPO, PR, HEAD2, "synchronize"),
                ItTarballs.singleFile("src/A.java", "class A { int b; }\n"), "diff-2");
        assertThat(run2).isNotNull();

        // 4) Planner 扫描：世代 gate 拒绝 → EXPIRED（不建 Run、不铸命令）
        assertThat(harness.newRepairPlanner().runOnce()).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT state FROM repair_request").query(String.class).single())
                .isEqualTo("EXPIRED");
        assertThat(adminJdbc.sql("SELECT last_error FROM repair_request").query(String.class).single())
                .isEqualTo("STALE_GENERATION");
        assertThat(adminJdbc.sql("SELECT count(*) FROM repair_request "
                        + "WHERE repair_run_id IS NULL AND repair_operation_id IS NULL")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT count(*) FROM execution_event WHERE event_type='REPAIR_EXPIRED' "
                        + "AND payload->>'reason'='STALE_GENERATION'")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT count(*) FROM review_run WHERE run_mode='REPAIR'")
                .query(Long.class).single()).isZero();
        assertThat(count("outbox_command")).isEqualTo(2); // 仍是首发两条，零新增

        // 5) 零远端写：全程只有首发两次 POST，无任何 PATCH/补写
        wiremock.verify(exactly(1), postRequestedFor(urlEqualTo("/repos/" + REPO + "/check-runs")));
        wiremock.verify(exactly(1),
                postRequestedFor(urlEqualTo("/repos/" + REPO + "/pulls/" + PR + "/reviews")));
        wiremock.verify(exactly(0),
                patchRequestedFor(urlPathMatching("/repos/" + REPO + "/check-runs/.*")));
    }
}
