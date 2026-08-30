package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.control.domain.model.ReviewRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ST-04 崩溃且远端未创建（F4/§4.3）：IN_FLIGHT 提交后、触网前 kill publisher
 * （stub 从未收到请求）→ 重启 → RECONCILING → 窗口内穷尽确认不存在 → RETRY_WAIT
 * → 重发成功 exactly 1 次。
 */
class ST04CrashRemoteMissingRetryIT extends PostgresITBase {

    private static final String HEAD_SHA = "head" + "e".repeat(36);

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
    void crashBeforeRemoteCallRetriesSafely() {
        ReviewRun run = harness.dispatchOpened(
                ItHarness.prEvent("st04-d1", 2004L, "objwww/mall", 24, HEAD_SHA, "opened"),
                ItTarballs.singleFile("src/A.java", "class A {}\n"), "diff");
        harness.modelClient.enqueueContent("[]");
        harness.newWorker("worker-1").runOnce();
        UUID checkOp = outboxOps().get(0);

        // T3-A 提交（IN_FLIGHT 落库）后、触网前"被杀"：stub 从未收到 POST
        CrashyPublicationStore crashy = new CrashyPublicationStore(harness.postgresStore);
        crashy.crashAfterPrepareNext();
        harness.sabotageStore(crashy);
        harness.newClaimer().runOnce();
        assertThat(stateOf(checkOp)).isEqualTo("IN_FLIGHT");
        wiremock.verify(exactly(0), postRequestedFor(urlEqualTo("/repos/objwww/mall/check-runs")));

        // 租约过期 → scanner：RECONCILING → 探针窗口内穷尽（空页 = 短页）→ 确认不存在 → RETRY_WAIT
        adminJdbc.sql("UPDATE outbox_command SET lease_until = :past WHERE operation_id = :id")
                .param("past", Timestamp.from(Instant.now().minusSeconds(5)))
                .param("id", checkOp).update();
        harness.restoreStore();
        wiremock.stubFor(get(urlEqualTo("/repos/objwww/mall/commits/" + HEAD_SHA
                        + "/check-runs?per_page=100&page=1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"check_runs\":[]}")));
        harness.newScanner().runOnce();
        assertThat(stateOf(checkOp)).isEqualTo("RETRY_WAIT");

        // 退避到期 → 重发成功，远端只收到这 1 次 POST
        adminJdbc.sql("UPDATE outbox_command SET next_attempt_at = :past WHERE operation_id = :id")
                .param("past", Timestamp.from(Instant.now().minusSeconds(1)))
                .param("id", checkOp).update();
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/mall/check-runs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":556,\"html_url\":\"http://x/556\"}")));
        harness.newClaimer().runOnce();

        assertThat(stateOf(checkOp)).isEqualTo("CONFIRMED");
        wiremock.verify(exactly(1), postRequestedFor(urlEqualTo("/repos/objwww/mall/check-runs")));
        assertThat(run).isNotNull();
    }

    private List<UUID> outboxOps() {
        return adminJdbc.sql("SELECT operation_id FROM outbox_command ORDER BY aggregate_sequence")
                .query((rs, n) -> rs.getObject(1, UUID.class)).list();
    }

    private String stateOf(UUID operationId) {
        return adminJdbc.sql("SELECT state FROM outbox_command WHERE operation_id = :id")
                .param("id", operationId).query(String.class).single();
    }
}
