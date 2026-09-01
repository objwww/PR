package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
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
 * ST-36（M2 方案 §11/L3，回指 I26/§4.3 新行模型）。
 * 场景（注入）：CHECK_RUN 修复成功（旧行 REPAIRED + 新行 PRESENT 链回）后，远端对象
 * 再次被删（stub 探针列表复空），进入第二轮巡检。
 * 预期断言：允许生成新 repair 单并修复完成——旧链 REPAIRED 不占活跃单唯一索引、不挡新单；
 * 两轮资源行链完整（旧 REPAIRED → 二轮 REPAIRED → 三轮 PRESENT，replaces_resource_id
 * 逐环链回）；新行在巡检面（拨到期后探针 FOUND 正常刷新 PRESENT，不再铸单）。
 * 取证：publication_resource 三行链 + repair_request 两行均 REPAIRED + stub journal 写计数。
 */
class St36RepairSecondRoundIT extends PostgresITBase {

    private static final String REPO = "objwww/mall";
    private static final int PR = 36;
    private static final long REPOSITORY_ID = 2036L;
    private static final String HEAD = "head" + "9".repeat(36);
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

    private List<String> checkRowStates() {
        return adminJdbc.sql("SELECT state FROM publication_resource WHERE resource_type='CHECK_RUN' "
                        + "ORDER BY created_at").query(String.class).list();
    }

    @Test
    void secondDeleteAfterRepairAllowsNewRequestAndRepairs() {
        // 1) 完整发布一轮
        harness.dispatchOpened(ItHarness.prEvent("st36-d1", REPOSITORY_ID, REPO, PR, HEAD, "opened"),
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

        // 2) 第一轮：远端被删（探针空 + sanity 通过）→ MISSING + AUTO 单 → 修复（重建为 1002）
        adminJdbc.sql("UPDATE publication_resource SET next_check_at=CASE WHEN resource_type='CHECK_RUN' "
                        + "THEN now()-interval '1 second' ELSE now()+interval '1 day' END").update();
        wiremock.stubFor(get(urlEqualTo(CHECK_PROBE_URL))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"check_runs\":[]}")));
        wiremock.stubFor(get(urlEqualTo("/repos/" + REPO))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"full_name\":\"" + REPO + "\"}")));
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(harness.newRepairPlanner().runOnce()).isEqualTo(1);
        wiremock.stubFor(post(urlEqualTo("/repos/" + REPO + "/check-runs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1002,\"html_url\":\"http://x/check/1002\"}")));
        harness.newClaimer().runOnce();
        assertThat(checkRowStates()).containsExactly("REPAIRED", "PRESENT");
        assertThat(adminJdbc.sql("SELECT state FROM repair_request").query(String.class).single())
                .isEqualTo("REPAIRED");
        assertThat(harness.newRepairPlanner().runOnce()).isEqualTo(1); // 收口 REPAIR Run

        // 3) 第二轮：新 PRESENT 行到期 + 远端再次被删 → 旧链 REPAIRED 不挡新单
        adminJdbc.sql("UPDATE publication_resource SET next_check_at=now()-interval '1 second' "
                        + "WHERE resource_type='CHECK_RUN' AND state='PRESENT'").update();
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT count(*) FROM repair_request").query(Long.class).single())
                .isEqualTo(2); // 新单成功铸造（旧单 REPAIRED 不占活跃唯一索引）
        assertThat(adminJdbc.sql("SELECT state FROM repair_request ORDER BY created_at")
                .query(String.class).list()).containsExactly("REPAIRED", "PENDING");
        assertThat(adminJdbc.sql("SELECT count(*) FROM execution_event "
                        + "WHERE event_type='PUBLICATION_DRIFT_DETECTED'")
                .query(Long.class).single()).isEqualTo(2); // 两轮各一次

        // 4) 第二轮修复：铸命令 → probe-first NotFound → 重建为 1003 → 替换链收口
        assertThat(harness.newRepairPlanner().runOnce()).isEqualTo(1);
        wiremock.stubFor(post(urlEqualTo("/repos/" + REPO + "/check-runs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1003,\"html_url\":\"http://x/check/1003\"}")));
        harness.newClaimer().runOnce();

        // 三行链：首发行 REPAIRED（remote_id 1001 保留）→ 二轮行 REPAIRED（1002）→ 三轮行 PRESENT（1003）
        assertThat(checkRowStates()).containsExactly("REPAIRED", "REPAIRED", "PRESENT");
        assertThat(adminJdbc.sql("""
                SELECT count(*) FROM publication_resource r
                WHERE (r.remote_id='1001' AND r.state='REPAIRED' AND r.replaces_resource_id IS NULL)
                   OR (r.remote_id='1002' AND r.state='REPAIRED' AND r.replaces_resource_id =
                       (SELECT id FROM publication_resource WHERE remote_id='1001'))
                   OR (r.remote_id='1003' AND r.state='PRESENT' AND r.replaces_resource_id =
                       (SELECT id FROM publication_resource WHERE remote_id='1002'))
                """).query(Long.class).single()).isEqualTo(3);
        assertThat(adminJdbc.sql("SELECT state FROM repair_request ORDER BY created_at")
                .query(String.class).list()).containsExactly("REPAIRED", "REPAIRED");
        wiremock.verify(exactly(3), postRequestedFor(urlEqualTo("/repos/" + REPO + "/check-runs")));

        // 5) 新行在巡检面：拨到期 + 探针 FOUND（external_id=第二轮 repair 命令）→ 刷新 PRESENT，零新单
        UUID secondRepairOp = adminJdbc.sql("SELECT repair_operation_id FROM repair_request "
                        + "ORDER BY created_at DESC LIMIT 1").query(UUID.class).single();
        wiremock.stubFor(get(urlEqualTo(CHECK_PROBE_URL))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"check_runs\":[{\"id\":1003,\"external_id\":\"" + secondRepairOp
                                + "\",\"html_url\":\"http://x/check/1003\"}]}")));
        adminJdbc.sql("UPDATE publication_resource SET next_check_at=now()-interval '1 second' "
                        + "WHERE remote_id='1003'").update();
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT state FROM publication_resource WHERE remote_id='1003'")
                .query(String.class).single()).isEqualTo("PRESENT");
        assertThat(adminJdbc.sql("SELECT count(*) FROM repair_request").query(Long.class).single())
                .isEqualTo(2); // 不再铸单
        assertThat(adminJdbc.sql("SELECT count(*) FROM execution_event "
                        + "WHERE event_type='PUBLICATION_DRIFT_DETECTED'")
                .query(Long.class).single()).isEqualTo(2);

        // 6) Planner 收口第二轮 REPAIR Run
        assertThat(harness.newRepairPlanner().runOnce()).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT r.state FROM review_run r JOIN repair_request rr "
                        + "ON rr.repair_run_id=r.id ORDER BY rr.created_at")
                .query(String.class).list()).containsExactly("COMPLETED", "COMPLETED");
    }
}
