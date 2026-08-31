package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.control.application.ProjectionSyncCommand;
import com.objwww.pr.control.application.PublicationRequest;
import com.objwww.pr.control.domain.model.PrSubjectState;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ST-19 换届扫尾 publisher 半段（M1-T06 扩展面）：M0 只演示 push 换届后的级联（CT-06）；
 * M1 新增 T-close / T-draft 两条换届源，本条验证它们 bump epoch 后，OutboxRecoveryScanner
 * 路径③对该 subject 全部旧 epoch PENDING 命令照常 SUPERSEDED + REQUIRE_* 级联 + 游标推进，
 * IN_FLIGHT 不动（I7）。防什么：Close/T-draft 的换届若逃过扫尾，M0 的级联保证被 M1 静默
 * 破坏（E2E-12）。control 侧 half（epoch 递增/幂等分支断言）在 AuthoritativeRoutingIT。
 */
class ST19StaleEpochSweepIT extends PostgresITBase {

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

    private Map<String, Object> outboxRow(UUID operationId) {
        return adminJdbc.sql("SELECT * FROM outbox_command WHERE operation_id = :id")
                .param("id", operationId)
                .query((rs, n) -> Map.<String, Object>of(
                        "state", rs.getString("state"),
                        "last_error_code", rs.getString("last_error_code") == null
                                ? "" : rs.getString("last_error_code")))
                .single();
    }

    @Test
    void closeGenerationTriggersSweepCascade() {
        ReviewRun run = harness.runIntakeDirect(
                ItHarness.prEvent("st19-d1", 1019L, "objwww/mall", 19,
                        "head" + "9".repeat(36), "opened"),
                Digest.sha256Of("st19-diff"), Digest.sha256Of("st19-snapshot"));
        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(1019L, 19)
                .orElseThrow().getId();
        String agg = "pr:1019#19";
        Map<String, Object> checkPayload = Map.of("repo", "objwww/mall",
                "head_sha", "head" + "9".repeat(36), "name", "ai-code-review", "finding_count", 0);

        // A1 PENDING；A2 REQUIRE_CONFIRMED→A1；A3 构造为 IN_FLIGHT（租约远未到期，I7 不动）
        var a1 = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(), agg,
                CommandType.CREATE_CHECK, checkPayload, List.of());
        var a2 = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(), agg,
                CommandType.CREATE_CHECK, checkPayload, List.of(
                        PublicationRequest.DependencyEdge.requireConfirmed(a1.operationId())));
        var a3 = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(), agg,
                CommandType.CREATE_CHECK, checkPayload, List.of());
        adminJdbc.sql("UPDATE outbox_command SET state = 'IN_FLIGHT', lease_owner = 'x'," +
                        " lease_until = now() + interval '1 hour' WHERE operation_id = :id")
                .param("id", a3.operationId().value()).update();

        // M1 T-close：closeGeneration 换届（epoch 1→2），旧世代命令全部落后
        harness.orchestrator().closeGeneration(new ProjectionSyncCommand(
                ItHarness.INSTALLATION_ID, 1019L, "objwww/mall", 19,
                PrSubjectState.CLOSED, false, false, ItHarness.POLICY, Instant.now()));
        assertThat(subjectCursor(subjectId)[0]).isEqualTo(2);

        harness.newScanner().runOnce();

        assertThat(outboxRow(a1.operationId().value()))
                .containsEntry("state", "SUPERSEDED").containsEntry("last_error_code", "STALE_EPOCH");
        assertThat(outboxRow(a2.operationId().value()))
                .containsEntry("state", "SUPERSEDED").containsEntry("last_error_code", "CASCADE_SUPERSEDED");
        assertThat(outboxRow(a3.operationId().value())).containsEntry("state", "IN_FLIGHT");
        // 游标同事务连续推进：A1/A2 解决，A3 卡住后续
        assertThat(subjectCursor(subjectId)[2]).isEqualTo(2);
    }

    @Test
    void convertToDraftGenerationAlsoTriggersSweepCascade() {
        ReviewRun run = harness.runIntakeDirect(
                ItHarness.prEvent("st19-d2", 1019L, "objwww/mall", 20,
                        "head" + "8".repeat(36), "opened"),
                Digest.sha256Of("st19d-diff"), Digest.sha256Of("st19d-snapshot"));
        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(1019L, 20)
                .orElseThrow().getId();
        Map<String, Object> checkPayload = Map.of("repo", "objwww/mall",
                "head_sha", "head" + "8".repeat(36), "name", "ai-code-review", "finding_count", 0);
        var a1 = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(), "pr:1019#20",
                CommandType.CREATE_CHECK, checkPayload, List.of());

        // M1 T-draft：convertToDraftGeneration 同样换届
        harness.orchestrator().convertToDraftGeneration(new ProjectionSyncCommand(
                ItHarness.INSTALLATION_ID, 1019L, "objwww/mall", 20,
                PrSubjectState.OPEN, true, false, ItHarness.POLICY, Instant.now()));
        assertThat(subjectCursor(subjectId)[0]).isEqualTo(2);

        harness.newScanner().runOnce();

        assertThat(outboxRow(a1.operationId().value()))
                .containsEntry("state", "SUPERSEDED").containsEntry("last_error_code", "STALE_EPOCH");
        assertThat(subjectCursor(subjectId)[2]).isEqualTo(1);
    }

    @Test
    void confirmedSurvivesGenerationSweepAndNewGenerationStaysIndependent() {
        // CONFIRMED 跨换届保持（设计语义，读码确认）：sweep 只扫 PENDING/RETRY_WAIT
        // （findStaleEpoch 的 WHERE 与 supersedeStaleEpoch 的锁内复核都不含 CONFIRMED）；
        // OutboxState 八态机刻意无 CONFIRMED_STALE——CONFIRMED 是"副作用已存在"的历史口径，
        // 换届不改写历史；资源现状归 publication_resource/drift 巡检管，sweep 不触碰。
        ReviewRun run = harness.runIntakeDirect(
                ItHarness.prEvent("st19-d3", 1020L, "objwww/mall", 22,
                        "head" + "5".repeat(36), "opened"),
                Digest.sha256Of("st19c-diff"), Digest.sha256Of("st19c-snapshot"));
        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(1020L, 22)
                .orElseThrow().getId();
        String agg = "pr:1020#22";
        Map<String, Object> checkPayload = Map.of("repo", "objwww/mall",
                "head_sha", "head" + "5".repeat(36), "name", "ai-code-review", "finding_count", 0);

        // A1 走到 CONFIRMED（旧世代完成发布）+ 资源 PRESENT
        var a1 = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(), agg,
                CommandType.CREATE_CHECK, checkPayload, List.of());
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/mall/check-runs"))
                .atPriority(1)
                .withRequestBody(containing(a1.operationId().toString()))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":5005,\"html_url\":\"http://x/5005\"}")));
        harness.newClaimer().runOnce();
        assertThat(outboxRow(a1.operationId().value())).containsEntry("state", "CONFIRMED");
        assertThat(count("publication_resource")).isEqualTo(1);

        // 换届 sweep：CONFIRMED 不被级联改动，旧资源不被触碰
        harness.orchestrator().closeGeneration(new ProjectionSyncCommand(
                ItHarness.INSTALLATION_ID, 1020L, "objwww/mall", 22,
                PrSubjectState.CLOSED, false, false, ItHarness.POLICY, Instant.now()));
        assertThat(subjectCursor(subjectId)[0]).isEqualTo(2);
        harness.newScanner().runOnce();

        assertThat(outboxRow(a1.operationId().value()))
                .containsEntry("state", "CONFIRMED").containsEntry("last_error_code", "");
        assertThat(adminJdbc.sql("SELECT state FROM publication_resource")
                .query(String.class).list()).containsExactly("PRESENT");

        // 新世代命令独立成链：epoch=2、seq 接续，可正常领取 CONFIRMED，不污染旧资源
        var b1 = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(), agg,
                CommandType.CREATE_CHECK, checkPayload, List.of());
        assertThat(adminJdbc.sql(
                        "SELECT publication_epoch, aggregate_sequence FROM outbox_command" +
                                " WHERE operation_id = :id")
                .param("id", b1.operationId().value())
                .query((rs, n) -> rs.getLong(1) + "/" + rs.getLong(2)).single())
                .isEqualTo("2/2");
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/mall/check-runs"))
                .atPriority(1)
                .withRequestBody(containing(b1.operationId().toString()))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":6006,\"html_url\":\"http://x/6006\"}")));
        harness.newClaimer().runOnce();

        assertThat(outboxRow(b1.operationId().value())).containsEntry("state", "CONFIRMED");
        assertThat(subjectCursor(subjectId)[2]).isEqualTo(2);
        // 两条资源各自独立：旧的仍挂 A1 且 PRESENT，新的挂 B1
        assertThat(adminJdbc.sql("""
                SELECT created_by_operation_id::text, remote_id, state FROM publication_resource
                 ORDER BY remote_id
                """).query((rs, n) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3))
                .list()).containsExactly(
                a1.operationId().toString() + "|5005|PRESENT",
                b1.operationId().toString() + "|6006|PRESENT");
    }
}
