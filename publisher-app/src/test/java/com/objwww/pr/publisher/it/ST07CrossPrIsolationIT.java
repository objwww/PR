package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ST-07 跨 PR 隔离：两 PR 并发评审——各自 sequence 空间独立、互不阻塞。
 * PR-A 的 GitHub 写持续 500（RETRY_WAIT），PR-B 必须照常全量 CONFIRMED。
 */
class ST07CrossPrIsolationIT extends PostgresITBase {

    private static final String HEAD_A = "aa" + "1".repeat(38);
    private static final String HEAD_B = "bb" + "2".repeat(38);

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
    void perPrSequenceSpacesAreIndependent() {
        // 两个 PR（不同仓库）各自 intake + 评审完成
        harness.dispatchOpened(ItHarness.prEvent("st07-a", 2007L, "objwww/repo-a", 27,
                HEAD_A, "opened"), ItTarballs.singleFile("A.java", "class A {}\n"), "diff-a");
        harness.dispatchOpened(ItHarness.prEvent("st07-b", 2008L, "objwww/repo-b", 28,
                HEAD_B, "opened"), ItTarballs.singleFile("B.java", "class B {}\n"), "diff-b");
        harness.modelClient.enqueueContent("[]").enqueueContent("[]");
        harness.newWorker("worker-1").runOnce(); // PR-A
        harness.newWorker("worker-1").runOnce(); // PR-B

        // 各自 sequence 空间从 1 开始（聚合键独立）
        assertThat(sequencesOf("pr:2007#27")).isEqualTo(List.of(1L, 2L));
        assertThat(sequencesOf("pr:2008#28")).isEqualTo(List.of(1L, 2L));

        // PR-A 的 GitHub 写 500；PR-B 正常
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/repo-a/check-runs"))
                .willReturn(aResponse().withStatus(500)));
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/repo-b/check-runs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"html_url\":\"http://x/1\"}")));
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/repo-b/pulls/28/reviews"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":2,\"html_url\":\"http://x/2\"}")));
        harness.newClaimer().runOnce();

        // PR-A：RETRY_WAIT + 依赖方 PENDING（阻塞不越序）；PR-B：全部 CONFIRMED
        assertThat(statesOf("pr:2007#27")).containsExactly("RETRY_WAIT", "PENDING");
        assertThat(statesOf("pr:2008#28")).containsExactly("CONFIRMED", "CONFIRMED");
        UUID subjectA = harness.subjectRepo.findByRepositoryAndPrNumber(2007L, 27).orElseThrow().getId();
        UUID subjectB = harness.subjectRepo.findByRepositoryAndPrNumber(2008L, 28).orElseThrow().getId();
        assertThat(subjectCursor(subjectA)[2]).isEqualTo(0); // A 游标未动
        assertThat(subjectCursor(subjectB)[2]).isEqualTo(2); // B 游标独立推进
    }

    private List<Long> sequencesOf(String aggregateKey) {
        return adminJdbc.sql("""
                SELECT aggregate_sequence FROM outbox_command
                 WHERE aggregate_key = :key ORDER BY aggregate_sequence
                """).param("key", aggregateKey).query(Long.class).list();
    }

    private List<String> statesOf(String aggregateKey) {
        return adminJdbc.sql("""
                SELECT state FROM outbox_command
                 WHERE aggregate_key = :key ORDER BY aggregate_sequence
                """).param("key", aggregateKey).query(String.class).list();
    }
}
