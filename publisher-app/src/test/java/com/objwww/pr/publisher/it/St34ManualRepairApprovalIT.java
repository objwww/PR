package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * ST-34（M2 方案 §11/L3，回指 I21/§4.3 分档 + runbook 审批）。
 * 场景（注入）：REVIEW 资源远端被删（探针列表空 + sanity 通过）→ RepairPolicy 分档
 * REVIEW 恒 MANUAL，单停 PENDING；人工按 runbook 参数化 SQL 批准（带 actor/reason）。
 * 预期断言：批准前 Planner 零拾取、零自动命令、零 REPAIR Run；批准（PENDING→APPROVED
 * + 审计三列）后走通完整修复链恰一次（铸单→probe-first NotFound→重发→新 PRESENT 行链
 * 回旧行→request REPAIRED）；审计三列齐全；重复批准 0 行（幂等）。
 * 取证：repair_request 全列（含 approved_by/approved_at/approval_reason） +
 * publication_resource 替换链 + stub journal 写计数。
 */
class St34ManualRepairApprovalIT extends PostgresITBase {

    private static final String REPO = "objwww/mall";
    private static final int PR = 34;
    private static final long REPOSITORY_ID = 2034L;
    private static final String HEAD = "head" + "7".repeat(36);

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
    void manualTierRepairsOnlyAfterRunbookApproval() {
        // 1) 完整发布一轮
        harness.dispatchOpened(ItHarness.prEvent("st34-d1", REPOSITORY_ID, REPO, PR, HEAD, "opened"),
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

        // 2) 只删 REVIEW：review 到期 + 探针空 + sanity 通过 → MISSING + MANUAL PENDING
        adminJdbc.sql("UPDATE publication_resource SET next_check_at=CASE WHEN resource_type='REVIEW' "
                        + "THEN now()-interval '1 second' ELSE now()+interval '1 day' END").update();
        wiremock.stubFor(get(urlEqualTo("/repos/" + REPO + "/pulls/" + PR + "/reviews"
                        + "?per_page=100&page=1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[]")));
        wiremock.stubFor(get(urlEqualTo("/repos/" + REPO))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"full_name\":\"" + REPO + "\"}")));
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT resource_type||':'||policy_tier||':'||state FROM repair_request")
                .query(String.class).single()).isEqualTo("REVIEW:MANUAL:PENDING");
        assertThat(adminJdbc.sql("SELECT state FROM publication_resource WHERE resource_type='REVIEW'")
                .query(String.class).single()).isEqualTo("MISSING");
        assertThat(adminJdbc.sql("SELECT count(*) FROM execution_event WHERE event_type='REPAIR_REQUESTED' "
                        + "AND payload->>'policy_tier'='MANUAL'")
                .query(Long.class).single()).isEqualTo(1);

        // 3) 批准前：Planner 零拾取（MANUAL PENDING 不在领取集），零自动命令、零 REPAIR Run
        assertThat(harness.newRepairPlanner().runOnce()).isZero();
        assertThat(count("outbox_command")).isEqualTo(2);
        assertThat(adminJdbc.sql("SELECT count(*) FROM review_run WHERE run_mode='REPAIR'")
                .query(Long.class).single()).isZero();
        wiremock.verify(exactly(1),
                postRequestedFor(urlEqualTo("/repos/" + REPO + "/pulls/" + PR + "/reviews")));

        // 4) runbook 批准（§4.3 参数化 SQL，control 角色持审批列写权）：带 actor/reason
        UUID requestId = adminJdbc.sql("SELECT id FROM repair_request").query(UUID.class).single();
        int approved = controlJdbc.sql("""
                UPDATE repair_request SET state='APPROVED', approved_by=:actor, approved_at=now(),
                    approval_reason=:reason WHERE id=:id AND state='PENDING'
                """).param("actor", "oncall-li").param("reason", "runbook#review-republish 确认重发")
                .param("id", requestId).update();
        assertThat(approved).isEqualTo(1);
        // 重复批准幂等：PENDING 守卫失配 → 0 行
        assertThat(controlJdbc.sql("""
                UPDATE repair_request SET state='APPROVED', approved_by=:actor, approved_at=now(),
                    approval_reason=:reason WHERE id=:id AND state='PENDING'
                """).param("actor", "oncall-li").param("reason", "dup").param("id", requestId).update())
                .isZero();

        // 5) 批准后走通完整修复链恰一次：铸命令 → probe-first 仍 NotFound → 重发 → 替换链收口
        assertThat(harness.newRepairPlanner().runOnce()).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT state FROM repair_request").query(String.class).single())
                .isEqualTo("DISPATCHED");
        wiremock.stubFor(post(urlEqualTo("/repos/" + REPO + "/pulls/" + PR + "/reviews"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":2003,\"html_url\":\"http://x/review/2003\"}")));
        harness.newClaimer().runOnce();

        assertThat(adminJdbc.sql("SELECT state FROM repair_request").query(String.class).single())
                .isEqualTo("REPAIRED");
        // 审计三列齐全且为批准时写入的值
        assertThat(adminJdbc.sql("SELECT count(*) FROM repair_request WHERE approved_by='oncall-li' "
                        + "AND approved_at IS NOT NULL AND approval_reason IS NOT NULL")
                .query(Long.class).single()).isEqualTo(1);
        // 恰一次：仅一条 repair 命令且 CONFIRMED；POST reviews 全程恰好 2 次（首发 + 修复重发）
        assertThat(adminJdbc.sql("SELECT state FROM outbox_command ORDER BY aggregate_sequence")
                .query(String.class).list())
                .containsExactly("CONFIRMED", "CONFIRMED", "CONFIRMED");
        assertThat(adminJdbc.sql("SELECT command_type FROM outbox_command "
                        + "ORDER BY aggregate_sequence DESC LIMIT 1").query(String.class).single())
                .isEqualTo("PUBLISH_REVIEW");
        wiremock.verify(exactly(2),
                postRequestedFor(urlEqualTo("/repos/" + REPO + "/pulls/" + PR + "/reviews")));
        // 替换链：旧行 REPAIRED 保原 remote_id；新行 PRESENT remote_id=2003 链回旧行
        assertThat(adminJdbc.sql("SELECT state FROM publication_resource ORDER BY created_at")
                .query(String.class).list()).containsExactly("PRESENT", "REPAIRED", "PRESENT");
        assertThat(adminJdbc.sql("SELECT count(*) FROM publication_resource WHERE resource_type='REVIEW' "
                        + "AND state='REPAIRED' AND remote_id='2001' AND repaired_by_operation_id IS NOT NULL")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT count(*) FROM publication_resource WHERE remote_id='2003' "
                        + "AND state='PRESENT' AND replaces_resource_id IS NOT NULL")
                .query(Long.class).single()).isEqualTo(1);

        // 6) Planner 收口零 Step REPAIR Run → COMPLETED
        assertThat(harness.newRepairPlanner().runOnce()).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT r.state FROM review_run r "
                        + "JOIN repair_request rr ON rr.repair_run_id=r.id")
                .query(String.class).single()).isEqualTo("COMPLETED");
    }
}
