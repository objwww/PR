package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.ExecutionEventType;
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
 * CT-07 游标语义（评审修正 #5 + E2）：
 * SUPERSEDED/FAILED_TERMINAL 后下一命令可正常领取（游标已推进）；
 * MANUAL 后下一命令不可领取（阻塞，保序 > 可用性）；跳号触发 SEQUENCE_GAP_DETECTED 对账事件。
 */
class CT07CursorSemanticsIT extends PostgresITBase {

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

    private String stateOf(UUID operationId) {
        return adminJdbc.sql("SELECT state FROM outbox_command WHERE operation_id = :id")
                .param("id", operationId).query(String.class).single();
    }

    private ReviewRun seedSubject(String delivery, long repoId, int prNumber, String headSha) {
        return harness.runIntakeDirect(ItHarness.prEvent(delivery, repoId, "objwww/mall", prNumber,
                headSha, "opened"), Digest.sha256Of(delivery + "-diff"),
                Digest.sha256Of(delivery + "-snap"));
    }

    @Test
    void supersededUnblocksNextCommand() {
        ReviewRun run = seedSubject("ct07a-d1", 1007L, 12, "head" + "8".repeat(36));
        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(1007L, 12)
                .orElseThrow().getId();
        String agg = "pr:1007#12";
        Map<String, Object> payload = Map.of("repo", "objwww/mall",
                "head_sha", "head" + "8".repeat(36), "name", "ai-code-review", "finding_count", 0);
        var b1 = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(), agg,
                CommandType.CREATE_CHECK, payload, List.of());
        var b2 = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(), agg,
                CommandType.CREATE_CHECK, payload, List.of());

        // 单批领取两条：B1 422 head 不匹配 → SUPERSEDED；同批 B2 游标已推进 → 放行 201
        // （专属桩必须 atPriority(1)：默认优先级 5 下后注册的泛化桩会遮蔽先注册的专属桩）
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/mall/check-runs"))
                .atPriority(1)
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .containing(b1.operationId().toString()))
                .willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"No commit found for SHA\"}")));
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/mall/check-runs"))
                .atPriority(5)
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":42,\"html_url\":\"http://x/42\"}")));
        harness.newClaimer().runOnce();

        assertThat(stateOf(b1.operationId().value())).isEqualTo("SUPERSEDED");
        assertThat(stateOf(b2.operationId().value())).isEqualTo("CONFIRMED");
        assertThat(subjectCursor(subjectId)[2]).isEqualTo(2);
        wiremock.verify(exactly(1), postRequestedFor(urlEqualTo("/repos/objwww/mall/check-runs"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .containing(b1.operationId().toString())));
        wiremock.verify(exactly(1), postRequestedFor(urlEqualTo("/repos/objwww/mall/check-runs"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .containing(b2.operationId().toString())));
    }

    @Test
    void manualBlocksNextCommandAndGapIsLedgered() {
        ReviewRun run = seedSubject("ct07b-d1", 1008L, 13, "head" + "9".repeat(36));
        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(1008L, 13)
                .orElseThrow().getId();
        String agg = "pr:1008#13";
        Map<String, Object> payload = Map.of("repo", "objwww/mall",
                "head_sha", "head" + "9".repeat(36), "name", "ai-code-review", "finding_count", 0);
        var c1 = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(), agg,
                CommandType.CREATE_CHECK, payload, List.of());
        var c2 = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(), agg,
                CommandType.CREATE_CHECK, payload, List.of());

        // GitHub 持续 500：C1 退避直到 attempt 预算耗尽 → MANUAL（EX-01 熔断）
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/mall/check-runs"))
                .willReturn(aResponse().withStatus(500)));
        for (int round = 0; round < 3; round++) {
            adminJdbc.sql("UPDATE outbox_command SET next_attempt_at = :past WHERE operation_id = :id")
                    .param("past", Timestamp.from(Instant.now().minusSeconds(1)))
                    .param("id", c1.operationId().value()).update();
            harness.newClaimer().runOnce();
        }
        assertThat(stateOf(c1.operationId().value())).isEqualTo("MANUAL");

        // C2 永不被放行：游标停在 0，seq2 跳号 → SEQUENCE_GAP_DETECTED + 释放租约不执行
        for (int round = 0; round < 2; round++) {
            harness.newClaimer().runOnce();
        }
        assertThat(stateOf(c2.operationId().value())).isEqualTo("PENDING");
        assertThat(subjectCursor(subjectId)[2]).isEqualTo(0);

        boolean gapLedgered = harness.eventsOf(run.getId()).stream()
                .anyMatch(e -> e.eventType() == ExecutionEventType.SEQUENCE_GAP_DETECTED);
        assertThat(gapLedgered).isTrue();

        // C2 对应的 external_id 从未出现在 GitHub 写请求里（保序阻塞的远端证据）
        wiremock.verify(exactly(3), postRequestedFor(urlEqualTo("/repos/objwww/mall/check-runs"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .containing(c1.operationId().toString())));
        wiremock.verify(exactly(0), postRequestedFor(urlEqualTo("/repos/objwww/mall/check-runs"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .containing(c2.operationId().toString())));
    }
}
