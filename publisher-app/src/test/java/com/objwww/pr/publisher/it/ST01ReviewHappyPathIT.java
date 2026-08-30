package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.model.RunStep;
import com.objwww.pr.control.domain.service.RunProjection;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.RunState;
import com.objwww.pr.shared.StepState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ST-01 评审主闭环：PR opened → 全链路（含 WorkItem Worker 真实消费）
 * → Check + Review 落 stub GitHub；outbox 全 CONFIRMED；projection 与账本 fold 一致。
 */
class ST01ReviewHappyPathIT extends PostgresITBase {

    private static final String HEAD_SHA = "head" + "a".repeat(36);
    private static final String FILE = "src/A.java";
    private static final String FILE_CONTENT =
            "package a;\n\npublic class A {\n    void f() {\n        s.length();\n    }\n}\n";

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
    void fullReviewLoop() {
        // 1) webhook → T0（假源真 CAS）→ T1（真 PG 事务）
        ReviewRun run = harness.dispatchOpened(
                ItHarness.prEvent("st01-d1", 2001L, "objwww/mall", 21, HEAD_SHA, "opened"),
                ItTarballs.singleFile(FILE, FILE_CONTENT),
                "diff --git a/src/A.java b/src/A.java\n+        s.length();\n");
        assertThat(run.getState()).isEqualTo(RunState.CREATED);
        assertThat(count("work_item")).isEqualTo(1);

        // 2) Worker 真实消费：模型桩返回 1 条 finding（锚点 "s.length();" 在文件第 5 行）
        harness.modelClient.enqueueContent("""
                [{"file":"src/A.java","line":1,"existing_code":"s.length();",
                  "rule":"npe-risk","severity":"MAJOR","message":"s 可能为 null"}]
                """);
        int processed = harness.newWorker("worker-1").runOnce();
        assertThat(processed).isEqualTo(1);

        // 3) Control 侧断言：Run/Step 推进、finding 精确定位在第 5 行、两条命令带依赖边
        assertThat(harness.runRepo.findById(run.getId()).orElseThrow().getState())
                .isEqualTo(RunState.REVIEW_COMPLETE);
        RunStep step = harness.stepRepo.findByRunId(run.getId()).get(0);
        assertThat(step.getState()).isEqualTo(StepState.SUCCEEDED);
        assertThat(count("review_finding")).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT line_start, line_end FROM review_finding")
                .query((rs, n) -> rs.getInt(1) + "-" + rs.getInt(2)).single()).isEqualTo("5-5");

        List<Map<String, Object>> commands = adminJdbc.sql("""
                SELECT operation_id, command_type, state, aggregate_sequence, publication_epoch
                  FROM outbox_command ORDER BY aggregate_sequence
                """).query((rs, n) -> Map.<String, Object>of(
                "id", rs.getObject("operation_id", UUID.class),
                "type", rs.getString("command_type"),
                "state", rs.getString("state"),
                "seq", rs.getLong("aggregate_sequence"),
                "epoch", rs.getLong("publication_epoch"))).list();
        assertThat(commands).hasSize(2);
        assertThat(commands.get(0)).containsEntry("type", "CREATE_CHECK")
                .containsEntry("state", "PENDING").containsEntry("seq", 1L);
        assertThat(commands.get(1)).containsEntry("type", "PUBLISH_REVIEW")
                .containsEntry("state", "PENDING").containsEntry("seq", 2L);
        assertThat(count("outbox_dependency")).isEqualTo(1); // PUBLISH_REVIEW REQUIRE_CONFIRMED CREATE_CHECK

        UUID checkOp = (UUID) commands.get(0).get("id");
        UUID reviewOp = (UUID) commands.get(1).get("id");

        // 4) Publisher 侧：真 GitHubWriteAdapter 打 WireMock
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/mall/check-runs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1001,\"html_url\":\"http://github.local/check/1001\"}")));
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/mall/pulls/21/reviews"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":2002,\"html_url\":\"http://github.local/review/2002\"}")));
        harness.newClaimer().runOnce();

        // 5) Publisher 侧断言：两条 CONFIRMED、游标推进、资源登记、远端各恰好一次
        assertThat(adminJdbc.sql("SELECT state FROM outbox_command ORDER BY aggregate_sequence")
                .query(String.class).list()).containsExactly("CONFIRMED", "CONFIRMED");
        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(2001L, 21)
                .orElseThrow().getId();
        assertThat(subjectCursor(subjectId)[2]).isEqualTo(2);
        assertThat(count("publication_resource")).isEqualTo(2);
        wiremock.verify(exactly(1), postRequestedFor(urlEqualTo("/repos/objwww/mall/check-runs"))
                .withRequestBody(containing("\"external_id\":\"" + checkOp + "\""))
                .withRequestBody(containing("\"name\":\"ai-code-review\"")));
        wiremock.verify(exactly(1), postRequestedFor(urlEqualTo("/repos/objwww/mall/pulls/21/reviews"))
                .withRequestBody(containing("<!-- ai-review:" + reviewOp + " -->")));

        // 6) 投影一致性：fold(events) == 实体行状态（I-投影一致性）
        RunProjection projection = harness.fold(run.getId());
        assertThat(projection.runState()).isEqualTo(RunState.REVIEW_COMPLETE);
        assertThat(projection.stepStates()).containsEntry(step.getId(), StepState.SUCCEEDED);
        assertThat(projection.publishedOperationIds()).containsExactlyInAnyOrder(checkOp, reviewOp);
        assertThat(harness.eventsOf(run.getId()).stream()
                .map(e -> e.eventType()).toList())
                .contains(ExecutionEventType.RUN_CREATED, ExecutionEventType.RUN_STATE_CHANGED,
                        ExecutionEventType.STEP_RESULT, ExecutionEventType.PUBLICATION_REQUESTED,
                        ExecutionEventType.PUBLICATION_CONFIRMED);
    }
}
