package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-27（M2 方案 §11/L4，回指 I20 + R-R5）IT 形态：探针歧义/超预算一律 UNKNOWN，
 * <b>不得任选一个执行</b>（单测已有一层，本类走真库 + 真探针编排）。
 *
 * <p>注入：① CHECK_RUN 探针同 external_id 双对象命中；② REVIEW 探针同一 body 内重复
 * marker、以及两个 review 各带同一 marker；③ 探针满页（100 条）未命中直至翻页预算
 * （probeMaxPages=2）耗尽。
 *
 * <p>断言：三种注入全部归 UNKNOWN → 探测失败处置（check_error_count 递增 + 退避），
 * 资源保持 PRESENT（绝不标 MISSING）；零漂移事件、零 repair_request（不铸单不执行）；
 * 分页预算封顶（恰 2 次 GET，无第 3 页）；歧义消除（恢复单命中）后下轮正常收敛
 * PRESENT 且 error_count 清零（无残留锁死）。
 *
 * <p>取证：publication_resource(state/check_error_count)、execution_event 零漂移事件、
 * repair_request 零行、WireMock 探针调用计数。
 *
 * <p>复原：每方法 TRUNCATE 全表（基座）+ 独立 WireMock 实例。
 */
class Ex27ProbeAmbiguityIT extends PostgresITBase {

    private static final String HEAD = "head" + "d".repeat(36);
    private static final String REPO = "objwww/mall";
    private static final int PR = 27;
    private static final long REPO_ID = 2127L;
    private static final String CHECK_PROBE =
            "/repos/" + REPO + "/commits/" + HEAD + "/check-runs?per_page=100&page=1";
    private static final String REVIEW_PROBE =
            "/repos/" + REPO + "/pulls/" + PR + "/reviews?per_page=100&page=1";

    private WireMockServer wiremock;
    private ItHarness harness;
    private ExRepairChain.Published published;

    @BeforeEach
    void setUp() {
        wiremock = new WireMockServer(wireMockConfig().dynamicPort());
        wiremock.start();
        harness = new ItHarness(casDir, wiremock.baseUrl());
        published = ExRepairChain.publishPair(harness, wiremock, "ex27-d1", REPO_ID, REPO, PR, HEAD);
    }

    @AfterEach
    void tearDown() {
        wiremock.stop();
    }

    @Test
    void multiObjectHitIsUnknownAndNeverPicksOne() {
        ExRepairChain.deferChecks("REVIEW");
        String checkOp = published.checkOperationId().toString();
        // 同 external_id 双对象命中：绝不认领首个命中（fail-closed 归 UNKNOWN）
        stubCheckProbe("{\"check_runs\":["
                + "{\"id\":1001,\"external_id\":\"" + checkOp + "\"},"
                + "{\"id\":1002,\"external_id\":\"" + checkOp + "\"}]}");

        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(checkRow("state")).isEqualTo("PRESENT"); // 不冒充 MISSING
        assertThat(checkRow("check_error_count")).isEqualTo("1");
        assertNoDriftNorRepair();

        // 歧义消除（远端恢复单命中）→ 下轮正常收敛，无残留锁死
        stubCheckProbe("{\"check_runs\":[{\"id\":1001,\"external_id\":\"" + checkOp + "\"}]}");
        ExRepairChain.expediteChecks("CHECK_RUN");
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(checkRow("state")).isEqualTo("PRESENT");
        assertThat(checkRow("check_error_count")).isEqualTo("0");
        assertNoDriftNorRepair();
    }

    @Test
    void duplicateMarkerIsUnknownAndNeverPicksOne() {
        ExRepairChain.deferChecks("CHECK_RUN");
        String marker = "<!-- ai-review:" + published.reviewOperationId() + " -->";

        // 形态一：同一 body 内重复 marker
        stubReviewProbe(ExRepairChain.reviewListJson("AI Code Review\n" + marker + "\n" + marker));
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(reviewRow("state")).isEqualTo("PRESENT");
        assertThat(reviewRow("check_error_count")).isEqualTo("1");

        // 形态二：两个 review 各带同一 marker
        ExRepairChain.expediteChecks("REVIEW");
        String body = ExRepairChain.zeroFindingsReviewBody(published.reviewOperationId());
        stubReviewProbe(ExRepairChain.reviewListJson(body, body));
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(reviewRow("state")).isEqualTo("PRESENT");
        assertThat(reviewRow("check_error_count")).isEqualTo("2");

        // 歧义轮绝不产生内容比对结论：零 CONTENT_DRIFTED、零漂移事件、零修复单
        assertThat(adminJdbc.sql(
                "SELECT count(*) FROM execution_event WHERE event_type = 'PUBLICATION_CONTENT_DRIFTED'")
                .query(Long.class).single()).isZero();
        assertNoDriftNorRepair();
    }

    @Test
    void pagingBudgetExhaustionIsUnknownNotMissing() {
        ExRepairChain.deferChecks("REVIEW");
        // 每页都满（100 条）但不含目标 external_id：窗口内无法穷尽，不得确认"不存在"
        wiremock.stubFor(get(urlPathEqualTo(
                "/repos/" + REPO + "/commits/" + HEAD + "/check-runs"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(fullCheckPageWithoutMatch())));

        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(checkRow("state")).isEqualTo("PRESENT"); // 超预算未命中 → UNKNOWN，不标 MISSING
        assertThat(checkRow("check_error_count")).isEqualTo("1");
        assertNoDriftNorRepair();

        // 翻页预算封顶（probeMaxPages=2）：恰 2 次 GET，无第 3 页
        wiremock.verify(exactly(2), getRequestedFor(urlPathEqualTo(
                "/repos/" + REPO + "/commits/" + HEAD + "/check-runs")));
    }

    private void stubCheckProbe(String body) {
        wiremock.stubFor(get(urlEqualTo(CHECK_PROBE))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private void stubReviewProbe(String body) {
        wiremock.stubFor(get(urlEqualTo(REVIEW_PROBE))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private String checkRow(String column) {
        return adminJdbc.sql("SELECT " + column + " FROM publication_resource WHERE id = :id")
                .param("id", published.checkResourceId()).query(String.class).single();
    }

    private String reviewRow(String column) {
        return adminJdbc.sql("SELECT " + column + " FROM publication_resource WHERE id = :id")
                .param("id", published.reviewResourceId()).query(String.class).single();
    }

    private void assertNoDriftNorRepair() {
        assertThat(adminJdbc.sql(
                "SELECT count(*) FROM execution_event WHERE event_type = 'PUBLICATION_DRIFT_DETECTED'")
                .query(Long.class).single()).isZero();
        assertThat(count("repair_request")).isZero();
    }

    /** 100 条不含目标 external_id 的满页（short-page 判定不触发，翻页到预算上限） */
    private static String fullCheckPageWithoutMatch() {
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
