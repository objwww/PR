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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-04 reconcile 翻页超窗口查不到（B12 半步）：每轮探测翻页预算封顶（probeMaxPages=2，
 * 满页未命中不能确认不存在 → UNKNOWN），reconcile_not_found_count 超预算 → MANUAL 熔断；
 * 全程零 POST（不盲目重发）。
 */
class EX04ReconcileWindowBudgetIT extends PostgresITBase {

    private static final String HEAD_SHA = "head" + "3".repeat(36);

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
    void probeBeyondWindowTripsToManual() {
        ReviewRun run = harness.runIntakeDirect(
                ItHarness.prEvent("ex04-d1", 3004L, "objwww/mall", 34, HEAD_SHA, "opened"),
                Digest.sha256Of("ex04-diff"), Digest.sha256Of("ex04-snap"));
        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(3004L, 34)
                .orElseThrow().getId();
        var command = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(),
                "pr:3004#34", CommandType.CREATE_CHECK,
                Map.of("repo", "objwww/mall", "head_sha", HEAD_SHA,
                        "name", "ai-code-review", "finding_count", 0), List.of());
        UUID opId = command.operationId().value();

        // 构造"崩溃现场"：IN_FLIGHT + 租约过期（绕过 prepare，直接测 scanner/探测路径）
        adminJdbc.sql("""
                UPDATE outbox_command SET state = 'IN_FLIGHT', lease_owner = 'dead-publisher',
                    lease_until = :past, lease_epoch = 1 WHERE operation_id = :id
                """).param("past", Timestamp.from(Instant.now().minusSeconds(5)))
                .param("id", opId).update();

        // 探针每页都返回满页（100 条）但不含目标 external_id：窗口内无法穷尽 → UNKNOWN
        wiremock.stubFor(get(urlPathEqualTo("/repos/objwww/mall/commits/" + HEAD_SHA + "/check-runs"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(fullPageWithoutMatch())));

        // 3 轮 scanner：not_found 计数 1 → 2 → 3 超预算（max=2）熔断 MANUAL
        for (int round = 1; round <= 3; round++) {
            adminJdbc.sql("UPDATE outbox_command SET reconcile_after = :past WHERE operation_id = :id")
                    .param("past", Timestamp.from(Instant.now().minusSeconds(1)))
                    .param("id", opId).update();
            harness.newScanner().runOnce();
        }

        Map<String, Object> row = adminJdbc.sql("""
                SELECT state, last_error_code, reconcile_not_found_count
                  FROM outbox_command WHERE operation_id = :id
                """).param("id", opId)
                .query((rs, n) -> Map.<String, Object>of("state", rs.getString("state"),
                        "err", rs.getString("last_error_code"),
                        "count", rs.getInt("reconcile_not_found_count"))).single();
        assertThat(row).containsEntry("state", "MANUAL")
                .containsEntry("err", "RECONCILE_BUDGET_EXCEEDED")
                .containsEntry("count", 3);
        // 翻页预算封顶：每轮恰好 2 页（probeMaxPages），共 6 次 GET；全程零 POST
        wiremock.verify(exactly(6), getRequestedFor(
                urlPathEqualTo("/repos/objwww/mall/commits/" + HEAD_SHA + "/check-runs")));
        wiremock.verify(exactly(0), postRequestedFor(urlEqualTo("/repos/objwww/mall/check-runs")));
    }

    /** 100 条不含目标 external_id 的满页（short-page 判定不触发，翻页到预算上限） */
    private static String fullPageWithoutMatch() {
        StringBuilder sb = new StringBuilder("{\"check_runs\":[");
        for (int i = 0; i < 100; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(9000 + i)
                    .append(",\"external_id\":\"someone-else-").append(i).append("\"}");
        }
        return sb.append("]}").toString();
    }
}
