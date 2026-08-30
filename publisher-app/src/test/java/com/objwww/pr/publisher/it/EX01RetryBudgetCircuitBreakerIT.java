package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-01 GitHub 500 连续（B13 熔断半步）：RETRY_WAIT 退避 → attempt_count 达上限 → MANUAL，
 * 不无限打转；MANUAL 不推进游标（评审修正 #5），阻塞同 PR 后续命令。
 */
class EX01RetryBudgetCircuitBreakerIT extends PostgresITBase {

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
    void continuousServerErrorTripsToManual() {
        ReviewRun run = harness.runIntakeDirect(
                ItHarness.prEvent("ex01-d1", 3001L, "objwww/mall", 31,
                        "head" + "0".repeat(36), "opened"),
                Digest.sha256Of("ex01-diff"), Digest.sha256Of("ex01-snap"));
        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(3001L, 31)
                .orElseThrow().getId();
        var command = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(),
                "pr:3001#31", CommandType.CREATE_CHECK,
                Map.of("repo", "objwww/mall", "head_sha", "head" + "0".repeat(36),
                        "name", "ai-code-review", "finding_count", 0), List.of());

        wiremock.stubFor(post(urlEqualTo("/repos/objwww/mall/check-runs"))
                .willReturn(aResponse().withStatus(500)));

        // 3 轮：RETRY_WAIT(1) → RETRY_WAIT(2) → MANUAL（attempt 上限 3，第 3 次失败熔断）
        for (int round = 1; round <= 3; round++) {
            adminJdbc.sql("UPDATE outbox_command SET next_attempt_at = :past WHERE operation_id = :id")
                    .param("past", Timestamp.from(Instant.now().minusSeconds(1)))
                    .param("id", command.operationId().value()).update();
            harness.newClaimer().runOnce();
        }

        Map<String, Object> row = adminJdbc.sql("""
                SELECT state, attempt_count, last_error_code FROM outbox_command WHERE operation_id = :id
                """).param("id", command.operationId().value())
                .query((rs, n) -> {
                    // last_error_code 可为 NULL（命令仍 PENDING 时），Map.of 拒 null 会掩盖真实状态
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("state", rs.getString("state"));
                    m.put("attempts", rs.getInt("attempt_count"));
                    m.put("err", rs.getString("last_error_code"));
                    return m;
                }).single();
        assertThat(row).containsEntry("state", "MANUAL")
                .containsEntry("attempts", 2) // 第 3 次直接熔断不再记 RETRY_WAIT
                .containsEntry("err", "RETRY_BUDGET_EXHAUSTED");
        assertThat(subjectCursor(subjectId)[2]).isEqualTo(0); // MANUAL 不推进游标
        wiremock.verify(exactly(3), postRequestedFor(urlEqualTo("/repos/objwww/mall/check-runs")));
        // 不无限打转：再来一轮也不应再有 POST
        harness.newClaimer().runOnce();
        wiremock.verify(exactly(3), postRequestedFor(urlEqualTo("/repos/objwww/mall/check-runs")));
    }
}
