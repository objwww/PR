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
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-05 epoch 超前的命令（人为构造，v2.2 §3-6）：fence 判 RETRYABLE → DEFER 释放租约，
 * 不 fence 误杀；subject epoch 追平后正常放行。
 */
class EX05FutureEpochDeferIT extends PostgresITBase {

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
    void futureEpochCommandDefersInsteadOfDying() {
        ReviewRun run = harness.runIntakeDirect(
                ItHarness.prEvent("ex05-d1", 3005L, "objwww/mall", 35,
                        "head" + "4".repeat(36), "opened"),
                Digest.sha256Of("ex05-diff"), Digest.sha256Of("ex05-snap"));
        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(3005L, 35)
                .orElseThrow().getId();
        var command = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(),
                "pr:3005#35", CommandType.CREATE_CHECK,
                Map.of("repo", "objwww/mall", "head_sha", "head" + "4".repeat(36),
                        "name", "ai-code-review", "finding_count", 0), List.of());
        assertThat(command.publicationEpoch()).isEqualTo(1);

        // 人为构造 epoch 超前：subject 回拨到 0（等价"publisher 读到了陈旧 subject 快照"的镜像）
        adminJdbc.sql("UPDATE pr_subject SET publication_epoch = 0 WHERE id = :id")
                .param("id", subjectId).update();

        // fence RETRYABLE → DEFER：状态保持 PENDING、租约释放、零 HTTP、可再被领取
        harness.newClaimer().runOnce();
        harness.newClaimer().runOnce();
        assertThat(stateOf(command.operationId().value())).isEqualTo("PENDING");
        wiremock.verify(exactly(0), postRequestedFor(urlEqualTo("/repos/objwww/mall/check-runs")));

        // epoch 追平后正常放行
        adminJdbc.sql("UPDATE pr_subject SET publication_epoch = 1 WHERE id = :id")
                .param("id", subjectId).update();
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/mall/check-runs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"html_url\":\"http://x/1\"}")));
        harness.newClaimer().runOnce();
        assertThat(stateOf(command.operationId().value())).isEqualTo("CONFIRMED");
    }

    private String stateOf(UUID operationId) {
        return adminJdbc.sql("SELECT state FROM outbox_command WHERE operation_id = :id")
                .param("id", operationId).query(String.class).single();
    }
}
