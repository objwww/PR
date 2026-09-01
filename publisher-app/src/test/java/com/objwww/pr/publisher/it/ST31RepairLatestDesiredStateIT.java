package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.OutboxState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ST-31（M2 方案 §11/L3，回指 I27/§4.3）。
 * 场景（注入）：CHECK_RUN 经 CREATE→UPDATE(completed) 后远端被删（stub 探针列表空 +
 * sanity 读通过）→ DriftReconciler 铸 AUTO 单 → RepairPlanner 铸 repair 命令 → 自动修复。
 * 预期断言：重建内容 = UPDATE 后的期望终态（非 CREATE 原型——CREATE 原型无 status/conclusion
 * 键，repair payload 出现这两个键即证明取自最新 UPDATE）；stub 写体断言 conclusion=completed
 * 族；不继承旧远端身份（无 check_run_id/remote_id/remote_url）。
 * 取证：stub journal 写体 + repair 命令 CAS payload + publication_resource 新 PRESENT 行链。
 */
class ST31RepairLatestDesiredStateIT extends PostgresITBase {

    private static final String REPO = "objwww/mall";
    private static final int PR = 31;
    private static final long REPOSITORY_ID = 2031L;
    private static final String HEAD = "head" + "3".repeat(36);
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
    void deletedCompletedCheckIsRecreatedFromLatestConfirmedPayload() {
        ReviewRun run = publishInitial();
        UUID subjectId = adminJdbc.sql("SELECT id FROM pr_subject WHERE github_repository_id=:repo")
                .param("repo", REPOSITORY_ID).query(UUID.class).single();

        var update = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(),
                "pr:" + REPOSITORY_ID + "#" + PR, CommandType.UPDATE_CHECK,
                Map.of("repo", REPO, "check_run_id", "1001", "status", "completed",
                        "conclusion", "success"), List.of());
        wiremock.stubFor(patch(urlEqualTo("/repos/" + REPO + "/check-runs/1001"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1001,\"html_url\":\"http://x/check/1001\"}")));
        harness.newClaimer().runOnce();
        assertThat(adminJdbc.sql("SELECT state FROM outbox_command WHERE operation_id=:id")
                .param("id", update.operationId().value()).query(String.class).single())
                .isEqualTo("CONFIRMED");

        // 只让 CHECK_RUN 到期；远端列表为空但 repo sanity 可读，形成 AUTO repair。
        adminJdbc.sql("UPDATE publication_resource SET next_check_at=CASE WHEN resource_type='CHECK_RUN' "
                        + "THEN now()-interval '1 second' ELSE now()+interval '1 day' END").update();
        wiremock.stubFor(get(urlEqualTo("/repos/" + REPO + "/commits/" + HEAD
                        + "/check-runs?per_page=100&page=1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"check_runs\":[]}")));
        wiremock.stubFor(get(urlEqualTo("/repos/" + REPO))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"full_name\":\"" + REPO + "\"}")));

        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT policy_tier||':'||state FROM repair_request")
                .query(String.class).single()).isEqualTo("AUTO:PENDING");
        assertThat(harness.newRepairPlanner().runOnce()).isEqualTo(1);

        // repair 命令 payload 必须来自 lineage 最新 CONFIRMED（UPDATE 终态），且不带旧远端身份
        UUID resourceId = adminJdbc.sql("SELECT publication_resource_id FROM repair_request")
                .query(UUID.class).single();
        UUID repairOp = adminJdbc.sql("SELECT repair_operation_id FROM repair_request")
                .query(UUID.class).single();
        String payloadHash = adminJdbc.sql(
                        "SELECT payload_hash FROM outbox_command WHERE operation_id=:id")
                .param("id", repairOp).query(String.class).single();
        Map<String, Object> repairPayload =
                harness.payloadReader.read(new Digest(payloadHash.trim()));
        assertThat(repairPayload)
                .containsEntry("status", "completed")   // CREATE 原型无此键：取自 UPDATE 终态
                .containsEntry("conclusion", "success")
                .containsEntry("name", "ai-code-review")
                .containsEntry("head_sha", HEAD)
                .doesNotContainKeys("check_run_id", "remote_id", "remote_url");
        assertThat(repairPayload.get("repair_of_resource_id")).isEqualTo(resourceId.toString());

        wiremock.stubFor(post(urlEqualTo("/repos/" + REPO + "/check-runs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1002,\"html_url\":\"http://x/check/1002\"}")));
        harness.newClaimer().runOnce();

        assertThat(adminJdbc.sql("SELECT state FROM repair_request").query(String.class).single())
                .isEqualTo("REPAIRED");
        assertThat(adminJdbc.sql("SELECT state FROM publication_resource ORDER BY created_at")
                .query(String.class).list()).containsExactly("REPAIRED", "PRESENT", "PRESENT");
        assertThat(adminJdbc.sql("SELECT count(*) FROM publication_resource "
                        + "WHERE remote_id='1002' AND replaces_resource_id IS NOT NULL")
                .query(Long.class).single()).isEqualTo(1);
        wiremock.verify(postRequestedFor(urlEqualTo("/repos/" + REPO + "/check-runs"))
                .withRequestBody(equalToJson("{\"status\":\"completed\",\"conclusion\":\"success\"}",
                        true, true)));

        // 第二轮 Planner 只投影已终态 request 对应的零 Step REPAIR Run。
        assertThat(harness.newRepairPlanner().runOnce()).isEqualTo(1);
        assertThat(adminJdbc.sql("""
                SELECT r.state FROM review_run r JOIN repair_request rr ON rr.repair_run_id=r.id
                """).query(String.class).single()).isEqualTo("COMPLETED");
        assertThat(adminJdbc.sql("SELECT count(*) FROM run_step WHERE review_run_id IN "
                        + "(SELECT repair_run_id FROM repair_request)")
                .query(Long.class).single()).isZero();
    }

    private ReviewRun publishInitial() {
        ReviewRun run = harness.dispatchOpened(
                ItHarness.prEvent("st31-d1", REPOSITORY_ID, REPO, PR, HEAD, "opened"),
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
        assertThat(adminJdbc.sql("SELECT state FROM outbox_command ORDER BY aggregate_sequence")
                .query(String.class).list()).containsOnly(OutboxState.CONFIRMED.name());
        return run;
    }
}
