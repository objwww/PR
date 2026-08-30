package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.service.RunProjection;
import com.objwww.pr.shared.RunState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ST-02 Push 换届（F9 + v2.1 修订三）：评审中 synchronize 新 commit →
 * 旧 Run SUPERSEDED；旧 PENDING 命令被兜底扫描/fence 级联 SUPERSEDED 且游标推进；
 * GitHub 上零旧世代对象；新世代新 Run 重新评审并发布。
 */
class ST02PushSupersedeIT extends PostgresITBase {

    private static final String HEAD1 = "head" + "b".repeat(36);
    private static final String HEAD2 = "head" + "c".repeat(36);

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
    void pushSupersedesOldGeneration() {
        // 1) 旧世代：opened → 评审完成 → 2 条 PENDING 命令（epoch 1），publisher 尚未领取
        ReviewRun run1 = harness.dispatchOpened(
                ItHarness.prEvent("st02-d1", 2002L, "objwww/mall", 22, HEAD1, "opened"),
                ItTarballs.singleFile("src/A.java", "class A { int a = 1; }\n"), "diff-1");
        harness.modelClient.enqueueContent("[]");
        harness.newWorker("worker-1").runOnce();
        assertThat(harness.runRepo.findById(run1.getId()).orElseThrow().getState())
                .isEqualTo(RunState.REVIEW_COMPLETE);
        List<UUID> gen1Ops = outboxOps();
        assertThat(gen1Ops).hasSize(2);

        // 2) push：synchronize 新 head → 换届（epoch 2）+ 旧 Run SUPERSEDED + 新 Run 评审
        ReviewRun run2 = harness.dispatchOpened(
                ItHarness.prEvent("st02-d2", 2002L, "objwww/mall", 22, HEAD2, "synchronize"),
                ItTarballs.singleFile("src/A.java", "class A { int a = 2; }\n"), "diff-2");
        assertThat(run2.getId()).isNotEqualTo(run1.getId());
        assertThat(harness.runRepo.findById(run1.getId()).orElseThrow().getState())
                .isEqualTo(RunState.SUPERSEDED);
        harness.modelClient.enqueueContent("[]");
        harness.newWorker("worker-1").runOnce();

        // 3) Publisher：4 条命令一批领取——旧世代 fence 级联，新世代放行
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/mall/check-runs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"html_url\":\"http://x/1\"}")));
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/mall/pulls/22/reviews"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":2,\"html_url\":\"http://x/2\"}")));
        harness.newClaimer().runOnce();
        harness.newScanner().runOnce(); // 兜底扫描路径同样演示（幂等）

        // 4) 断言：旧世代 SUPERSEDED，新世代 CONFIRMED，游标连续推进到 4
        List<Map<String, Object>> commands = outboxRows();
        assertThat(commands).hasSize(4);
        assertThat(commands.get(0)).containsEntry("state", "SUPERSEDED");
        assertThat(commands.get(1)).containsEntry("state", "SUPERSEDED");
        assertThat(commands.get(2)).containsEntry("state", "CONFIRMED").containsEntry("epoch", 2L);
        assertThat(commands.get(3)).containsEntry("state", "CONFIRMED").containsEntry("epoch", 2L);
        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(2002L, 22)
                .orElseThrow().getId();
        assertThat(subjectCursor(subjectId)).containsExactly(2L, 5L, 4L);

        // 5) GitHub 上零旧世代对象：旧 operation_id 从未出现在任何写请求
        for (UUID op : gen1Ops) {
            wiremock.verify(exactly(0), postRequestedFor(urlEqualTo("/repos/objwww/mall/check-runs"))
                    .withRequestBody(containing(op.toString())));
            wiremock.verify(exactly(0), postRequestedFor(urlEqualTo("/repos/objwww/mall/pulls/22/reviews"))
                    .withRequestBody(containing(op.toString())));
        }
        wiremock.verify(exactly(1), postRequestedFor(urlEqualTo("/repos/objwww/mall/check-runs")));
        wiremock.verify(exactly(1), postRequestedFor(urlEqualTo("/repos/objwww/mall/pulls/22/reviews")));

        // 6) 投影一致性：两个 Run 的 fold 都必须还原行状态
        RunProjection p1 = harness.fold(run1.getId());
        assertThat(p1.runState()).isEqualTo(RunState.SUPERSEDED);
        RunProjection p2 = harness.fold(run2.getId());
        assertThat(p2.runState()).isEqualTo(RunState.REVIEW_COMPLETE);
    }

    private List<UUID> outboxOps() {
        return adminJdbc.sql("SELECT operation_id FROM outbox_command ORDER BY aggregate_sequence")
                .query((rs, n) -> rs.getObject(1, UUID.class)).list();
    }

    private List<Map<String, Object>> outboxRows() {
        return adminJdbc.sql("""
                SELECT state, publication_epoch FROM outbox_command ORDER BY aggregate_sequence
                """).query((rs, n) -> Map.<String, Object>of(
                "state", rs.getString("state"),
                "epoch", rs.getLong("publication_epoch"))).list();
    }
}
