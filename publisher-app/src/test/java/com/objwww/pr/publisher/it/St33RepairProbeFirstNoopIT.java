package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

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
 * ST-33（M2 方案 §11/L3，回指 I20/I26 + §4.3 probe-first 短路）。
 * 场景（注入）：CHECK_RUN 漂移检出（MISSING + AUTO 单）后，人工恢复远端对象
 * （stub 复位：探针重新按 external_id 命中原 check-run），随后修复链推进。
 * 预期断言：repair 命令执行时 probe-first 探针 FOUND → 零远端写短路 CONFIRMED；
 * 不建新资源行，旧行回 PRESENT + repaired_by_operation_id=repair op +
 * drift_detected_at 清空；request → REPAIRED。
 * 取证：stub journal 写计数 0 新增 + publication_resource 单行归位 + repair_request 终态
 * + execution_event(REPAIR_REPAIRED, via=probe_first)。
 */
class St33RepairProbeFirstNoopIT extends PostgresITBase {

    private static final String REPO = "objwww/mall";
    private static final int PR = 33;
    private static final long REPOSITORY_ID = 2033L;
    private static final String HEAD = "head" + "6".repeat(36);
    private static final String CHECK_PROBE_URL =
            "/repos/" + REPO + "/commits/" + HEAD + "/check-runs?per_page=100&page=1";

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
    void manualRestoreShortCircuitsRepairWithZeroWrite() {
        // 1) 完整发布一轮
        harness.dispatchOpened(ItHarness.prEvent("st33-d1", REPOSITORY_ID, REPO, PR, HEAD, "opened"),
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
        UUID createOp = adminJdbc.sql(
                        "SELECT operation_id FROM outbox_command WHERE command_type='CREATE_CHECK'")
                .query(UUID.class).single();

        // 2) 漂移检出：CHECK_RUN 到期；远端被删 + sanity 通过 → MISSING + AUTO PENDING
        adminJdbc.sql("UPDATE publication_resource SET next_check_at=CASE WHEN resource_type='CHECK_RUN' "
                        + "THEN now()-interval '1 second' ELSE now()+interval '1 day' END").update();
        wiremock.stubFor(get(urlEqualTo(CHECK_PROBE_URL))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"check_runs\":[]}")));
        wiremock.stubFor(get(urlEqualTo("/repos/" + REPO))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"full_name\":\"" + REPO + "\"}")));
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT state FROM publication_resource WHERE resource_type='CHECK_RUN'")
                .query(String.class).single()).isEqualTo("MISSING");
        assertThat(adminJdbc.sql("SELECT policy_tier||':'||state FROM repair_request")
                .query(String.class).single()).isEqualTo("AUTO:PENDING");

        // 3) 人工恢复远端（stub 复位）：探针重新按原 CREATE 的 external_id 命中
        wiremock.stubFor(get(urlEqualTo(CHECK_PROBE_URL))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"check_runs\":[{\"id\":1001,\"external_id\":\"" + createOp
                                + "\",\"html_url\":\"http://x/check/1001\"}]}")));

        // 4) 修复链推进：Planner 铸命令 → Claimer 执行（probe-first FOUND → 零写短路）
        assertThat(harness.newRepairPlanner().runOnce()).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT state FROM repair_request").query(String.class).single())
                .isEqualTo("DISPATCHED");
        harness.newClaimer().runOnce();

        UUID repairOp = adminJdbc.sql("SELECT repair_operation_id FROM repair_request")
                .query(UUID.class).single();
        assertThat(adminJdbc.sql("SELECT state FROM repair_request").query(String.class).single())
                .isEqualTo("REPAIRED");
        assertThat(adminJdbc.sql("SELECT state||':'||remote_id FROM outbox_command "
                        + "WHERE operation_id=:id").param("id", repairOp).query(String.class).single())
                .isEqualTo("CONFIRMED:1001");

        // 5) 旧行归位：单行回 PRESENT + repaired_by 链 + drift 观测列清空（不建新行）
        assertThat(count("publication_resource")).isEqualTo(2); // check + review，无新增
        assertThat(adminJdbc.sql("SELECT state FROM publication_resource WHERE resource_type='CHECK_RUN'")
                .query(String.class).single()).isEqualTo("PRESENT");
        assertThat(adminJdbc.sql("SELECT repaired_by_operation_id FROM publication_resource "
                        + "WHERE resource_type='CHECK_RUN'").query(UUID.class).single())
                .isEqualTo(repairOp);
        assertThat(adminJdbc.sql("SELECT count(*) FROM publication_resource "
                        + "WHERE resource_type='CHECK_RUN' AND drift_detected_at IS NULL "
                        + "AND replaces_resource_id IS NULL AND remote_id='1001'")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT count(*) FROM execution_event "
                        + "WHERE event_type='REPAIR_REPAIRED' AND payload->>'via'='probe_first'")
                .query(Long.class).single()).isEqualTo(1);

        // 6) 零远端写：全程只有首发两次 POST，无任何 PATCH/重建
        wiremock.verify(exactly(1), postRequestedFor(urlEqualTo("/repos/" + REPO + "/check-runs")));
        wiremock.verify(exactly(1),
                postRequestedFor(urlEqualTo("/repos/" + REPO + "/pulls/" + PR + "/reviews")));
        wiremock.verify(exactly(0),
                patchRequestedFor(urlPathMatching("/repos/" + REPO + "/check-runs/.*")));

        // 7) Planner 收口零 Step REPAIR Run → COMPLETED
        assertThat(harness.newRepairPlanner().runOnce()).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT r.state FROM review_run r "
                        + "JOIN repair_request rr ON rr.repair_run_id=r.id")
                .query(String.class).single()).isEqualTo("COMPLETED");
    }
}
