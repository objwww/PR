package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CT-06 级联 supersede（v2.1 修订二 + v2.2 E3 + 评审修正 #5）：
 * 前置 SUPERSEDED → PENDING 的 REQUIRE_* 依赖同事务级联 + 游标同事务推进；
 * OPTIONAL 依赖不级联；IN_FLIGHT 不级联（I7，先对账）。
 */
class CT06CascadeSupersedeIT extends PostgresITBase {

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
    void supersedeCascadesRequireConfirmedButNotOptionalNorInFlight() {
        // subject/run/revision + 4 条命令：A2 REQUIRE_CONFIRMED→A1，A3 OPTIONAL→A1，A4 REQUIRE_CONFIRMED→A1
        ReviewRun run = harness.runIntakeDirect(
                ItHarness.prEvent("ct06-d1", 1006L, "objwww/mall", 11,
                        "head" + "6".repeat(36), "opened"),
                Digest.sha256Of("ct06-diff"), Digest.sha256Of("ct06-snapshot"));
        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(1006L, 11)
                .orElseThrow().getId();
        String agg = "pr:1006#11";
        Map<String, Object> checkPayload = Map.of("repo", "objwww/mall",
                "head_sha", "head" + "6".repeat(36), "name", "ai-code-review", "finding_count", 0);

        var a1 = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(), agg,
                CommandType.CREATE_CHECK, checkPayload, List.of());
        var a2 = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(), agg,
                CommandType.CREATE_CHECK, checkPayload, List.of(
                        com.objwww.pr.control.application.PublicationRequest.DependencyEdge
                                .requireConfirmed(a1.operationId())));
        var a3 = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(), agg,
                CommandType.CREATE_CHECK, checkPayload, List.of(
                        new com.objwww.pr.control.application.PublicationRequest.DependencyEdge(
                                a1.operationId(), com.objwww.pr.shared.DependencyMode.OPTIONAL)));
        var a4 = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(), agg,
                CommandType.CREATE_CHECK, checkPayload, List.of(
                        com.objwww.pr.control.application.PublicationRequest.DependencyEdge
                                .requireConfirmed(a1.operationId())));
        assertThat(a1.aggregateSequence()).isEqualTo(1);
        assertThat(a4.aggregateSequence()).isEqualTo(4);

        // A4 构造为 IN_FLIGHT（I7 不级联场景）；随后 push 换届（epoch 1→2，旧世代全部落后）
        adminJdbc.sql("UPDATE outbox_command SET state = 'IN_FLIGHT', lease_owner = 'x'," +
                        " lease_until = now() + interval '1 hour' WHERE operation_id = :id")
                .param("id", a4.operationId().value()).update();
        harness.runIntakeDirect(ItHarness.prEvent("ct06-d2", 1006L, "objwww/mall", 11,
                "head" + "7".repeat(36), "synchronize"),
                Digest.sha256Of("ct06-diff2"), Digest.sha256Of("ct06-snapshot2"));
        assertThat(subjectCursor(subjectId)[0]).isEqualTo(2);

        // 任一 2xx stub（A1 应死于 fence 而非触网；若触网说明 gate 失效）
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/mall/check-runs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"html_url\":\"http://x/1\"}")));

        // publisher 领取旧世代命令：A1 fence REJECT_SUPERSEDE → SUPERSEDED + 同事务级联
        harness.newClaimer().runOnce();

        assertThat(outboxRow(a1.operationId().value()))
                .containsEntry("state", "SUPERSEDED").containsEntry("last_error_code", "STALE_EPOCH");
        // REQUIRE_CONFIRMED 依赖方同事务级联（E3）
        assertThat(outboxRow(a2.operationId().value()))
                .containsEntry("state", "SUPERSEDED").containsEntry("last_error_code", "CASCADE_SUPERSEDED");
        // OPTIONAL 不级联：A3 不死于 CASCADE_SUPERSEDED（若后续被自身 fence 收编，码是 STALE_EPOCH）
        assertThat(outboxRow(a3.operationId().value()).get("last_error_code"))
                .isNotEqualTo("CASCADE_SUPERSEDED");
        // IN_FLIGHT 不级联（I7）
        assertThat(outboxRow(a4.operationId().value())).containsEntry("state", "IN_FLIGHT");
        // 游标同事务推进：A1/A2 连续 resolve（评审修正 #5）
        assertThat(subjectCursor(subjectId)[2]).isGreaterThanOrEqualTo(2);
    }
}
