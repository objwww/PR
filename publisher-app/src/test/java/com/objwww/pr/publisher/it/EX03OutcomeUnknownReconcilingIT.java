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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-03 GitHub 超时（连接成功响应丢失，§4.3/B11）：不确定是状态不是异常——
 * → RECONCILING + PUBLICATION_OUTCOME_UNKNOWN 落账，禁盲目重发。
 */
class EX03OutcomeUnknownReconcilingIT extends PostgresITBase {

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
    void transportTimeoutGoesToReconcilingNotRetry() {
        ReviewRun run = harness.runIntakeDirect(
                ItHarness.prEvent("ex03-d1", 3003L, "objwww/mall", 33,
                        "head" + "2".repeat(36), "opened"),
                Digest.sha256Of("ex03-diff"), Digest.sha256Of("ex03-snap"));
        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(3003L, 33)
                .orElseThrow().getId();
        var command = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(),
                "pr:3003#33", CommandType.CREATE_CHECK,
                Map.of("repo", "objwww/mall", "head_sha", "head" + "2".repeat(36),
                        "name", "ai-code-review", "finding_count", 0), List.of());

        // stub 挂起 8s > 适配器 5s 超时 → 传输层失败（响应丢失窗口）
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/mall/check-runs"))
                .willReturn(aResponse().withStatus(201).withFixedDelay(8000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"html_url\":\"http://x/1\"}")));
        harness.newClaimer().runOnce();

        // 不盲目重发：RECONCILING + 事件落账；下一次领取不命中（RECONCILING 不在可领集）
        assertThat(stateOf(command.operationId().value())).isEqualTo("RECONCILING");
        harness.newClaimer().runOnce();
        wiremock.verify(exactly(1), postRequestedFor(urlEqualTo("/repos/objwww/mall/check-runs")));
        assertThat(harness.eventsOf(run.getId()).stream()
                .anyMatch(e -> e.eventType() == ExecutionEventType.PUBLICATION_OUTCOME_UNKNOWN)).isTrue();
    }

    private String stateOf(UUID operationId) {
        return adminJdbc.sql("SELECT state FROM outbox_command WHERE operation_id = :id")
                .param("id", operationId).query(String.class).single();
    }
}
