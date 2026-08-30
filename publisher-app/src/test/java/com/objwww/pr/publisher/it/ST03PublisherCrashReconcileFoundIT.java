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
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ST-03 Publisher 崩溃恢复（F4/§4.3，effectively-once 核心证据）：
 * IN_FLIGHT 后 kill publisher 进程、GitHub stub 实际已创建 → 重启 →
 * OutboxRecoveryScanner 收敛：RECONCILING → 按 external_id 找到 → CONFIRMED；
 * stub 调用计数证明无重复 POST。
 */
class ST03PublisherCrashReconcileFoundIT extends PostgresITBase {

    private static final String HEAD_SHA = "head" + "d".repeat(36);

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
    void crashAfterRemoteSuccessReconcilesToConfirmedWithoutDuplicatePost() {
        ReviewRun run = harness.dispatchOpened(
                ItHarness.prEvent("st03-d1", 2003L, "objwww/mall", 23, HEAD_SHA, "opened"),
                ItTarballs.singleFile("src/A.java", "class A {}\n"), "diff");
        harness.modelClient.enqueueContent("[]");
        harness.newWorker("worker-1").runOnce();
        UUID checkOp = outboxOps().get(0);

        // GitHub 实际已创建（201），但 publisher 在 T3-B confirm 提交前"被杀"
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/mall/check-runs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":555,\"html_url\":\"http://x/555\"}")));
        CrashyPublicationStore crashy = new CrashyPublicationStore(harness.postgresStore);
        crashy.crashOnConfirmNext();
        harness.sabotageStore(crashy);
        harness.newClaimer().runOnce();
        assertThat(stateOf(checkOp)).isEqualTo("IN_FLIGHT"); // 副作用已发生，账本没落下

        // 进程死亡 = 租约无人续：拨时间使其过期（测试动作）
        adminJdbc.sql("UPDATE outbox_command SET lease_until = :past WHERE operation_id = :id")
                .param("past", Timestamp.from(Instant.now().minusSeconds(5)))
                .param("id", checkOp).update();

        // 重启：scanner 收敛。探针：该 head SHA 下能找到 external_id == operation_id 的 check
        harness.restoreStore();
        wiremock.stubFor(get(urlEqualTo("/repos/objwww/mall/commits/" + HEAD_SHA
                        + "/check-runs?per_page=100&page=1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"check_runs\":[{\"id\":555,\"external_id\":\"" + checkOp
                                + "\",\"html_url\":\"http://x/555\"}]}")));
        harness.newScanner().runOnce();

        // 收敛到 CONFIRMED（不重复创建）；资源登记；事件落 PUBLICATION_CONFIRMED(via=reconcile)
        assertThat(stateOf(checkOp)).isEqualTo("CONFIRMED");
        assertThat(adminJdbc.sql(
                "SELECT remote_id FROM outbox_command WHERE operation_id = :id")
                .param("id", checkOp).query(String.class).single()).isEqualTo("555");
        wiremock.verify(exactly(1), postRequestedFor(urlEqualTo("/repos/objwww/mall/check-runs")));
        wiremock.verify(exactly(1), getRequestedFor(
                urlEqualTo("/repos/objwww/mall/commits/" + HEAD_SHA + "/check-runs?per_page=100&page=1")));
        assertThat(count("publication_resource")).isEqualTo(1);
        assertThat(harness.eventsOf(run.getId()).stream()
                .filter(e -> e.eventType().name().equals("PUBLICATION_CONFIRMED"))
                .map(e -> String.valueOf(e.payload().get("via"))).toList())
                .contains("reconcile");
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
