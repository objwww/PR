package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.ExecutionEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-09 Publisher 领到非白名单命令（B15 防代理滥用回归，M0 不应存在）：
 * T3-A schema 白名单拒绝 → FAILED_TERMINAL + SAFETY_REJECTED 告警落账，绝不触网。
 */
class EX09NonWhitelistCommandIT extends PostgresITBase {

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
    void nonWhitelistCheckNameRejectedFailClosed() {
        ReviewRun run = harness.runIntakeDirect(
                ItHarness.prEvent("ex09-d1", 3010L, "objwww/mall", 40,
                        "head" + "9".repeat(36), "opened"),
                Digest.sha256Of("ex09-diff"), Digest.sha256Of("ex09-snap"));
        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(3010L, 40)
                .orElseThrow().getId();
        // 非白名单 check 名（白名单只有 "ai-code-review"）
        var command = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(),
                "pr:3010#40", CommandType.CREATE_CHECK,
                Map.of("repo", "objwww/mall", "head_sha", "head" + "9".repeat(36),
                        "name", "evil-check", "finding_count", 0), List.of());

        harness.newClaimer().runOnce();

        Map<String, Object> row = adminJdbc.sql(
                "SELECT state, last_error_code FROM outbox_command WHERE operation_id = :id")
                .param("id", command.operationId().value())
                .query((rs, n) -> Map.<String, Object>of("state", rs.getString("state"),
                        "err", rs.getString("last_error_code"))).single();
        assertThat(row).containsEntry("state", "FAILED_TERMINAL")
                .containsEntry("err", "SCHEMA_REJECTED");
        assertThat(harness.eventsOf(run.getId()).stream()
                .anyMatch(e -> e.eventType() == ExecutionEventType.SAFETY_REJECTED)).isTrue();
        wiremock.verify(exactly(0), postRequestedFor(urlEqualTo("/repos/objwww/mall/check-runs")));
    }
}
