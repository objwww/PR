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
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-02 GitHub 422 二分（评审顺手修正 + §6.3）：head SHA 已变的确定性否定
 * → SUPERSEDED（last_error_code=STALE_HEAD）；参数错误类 422 → FAILED_TERMINAL。
 */
class EX02StaleHead422IT extends PostgresITBase {

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
    void headMismatch422SupersedesAndValidation422FailsTerminally() {
        ReviewRun run = harness.runIntakeDirect(
                ItHarness.prEvent("ex02-d1", 3002L, "objwww/mall", 32,
                        "head" + "1".repeat(36), "opened"),
                Digest.sha256Of("ex02-diff"), Digest.sha256Of("ex02-snap"));
        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(3002L, 32)
                .orElseThrow().getId();
        Map<String, Object> payload = Map.of("repo", "objwww/mall",
                "head_sha", "head" + "1".repeat(36), "name", "ai-code-review", "finding_count", 0);
        var stale = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(),
                "pr:3002#32", CommandType.CREATE_CHECK, payload, List.of());
        var invalid = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(),
                "pr:3002#32", CommandType.CREATE_CHECK, payload, List.of());

        wiremock.stubFor(post(urlEqualTo("/repos/objwww/mall/check-runs"))
                .withRequestBody(containing(stale.operationId().toString()))
                .willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"No commit found for SHA: head111\"}")));
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/mall/check-runs"))
                .withRequestBody(containing(invalid.operationId().toString()))
                .atPriority(5)
                .willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Validation Failed: name is too long\"}")));

        harness.newClaimer().runOnce();

        assertThat(rowOf(stale.operationId().value()))
                .containsEntry("state", "SUPERSEDED").containsEntry("err", "STALE_HEAD");
        assertThat(rowOf(invalid.operationId().value()))
                .containsEntry("state", "FAILED_TERMINAL").containsEntry("err", "GITHUB_422");
        // 两条都属"可推进游标"终态：seq1/2 连续 resolve
        assertThat(subjectCursor(subjectId)[2]).isEqualTo(2);
    }

    private Map<String, Object> rowOf(UUID operationId) {
        return adminJdbc.sql("SELECT state, last_error_code FROM outbox_command WHERE operation_id = :id")
                .param("id", operationId)
                .query((rs, n) -> Map.<String, Object>of("state", rs.getString("state"),
                        "err", rs.getString("last_error_code"))).single();
    }
}
