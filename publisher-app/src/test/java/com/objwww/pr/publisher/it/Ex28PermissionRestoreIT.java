package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-28（M2 方案 §11/L4，回指 §4.6）：权限撤销 → UNKNOWN/权限告警 → 权限恢复。
 *
 * <p>注入：CHECK_RUN 探针空列表（GitHub 以 404 隐藏私有资源的语义）+ sanity 读 404
 * （权限撤销，EX-17 同形态）；随后探针恢复单命中 + sanity 恢复 200（权限恢复）。
 *
 * <p>断言（分两方法）：
 * <ul>
 *   <li>{@link #unknownResourceDoesNotBlockPatrolOfOthers}——系统级无锁死：单个资源
 *       UNKNOWN 不阻塞同轮/后续巡检，其余资源照常收敛 PRESENT；权限告警恰好一次
 *       （UNKNOWN 重复扫描不重复告警）；零漂移事件、零 repair_request（权限异常
 *       绝不冒充"不存在"）。</li>
 *   <li>{@link #permissionRestoredResourceReconvergesToPatrol}——EX-28 v1.2 裁定
 *       （人工复位制）：权限恢复后 UNKNOWN 资源<b>不自动回队</b>（M1 "UNKNOWN 不在巡检
 *       扫描集"既定行为维持）；由 runbook 人工 SQL 复位拨回 PRESENT 后，下一轮巡检
 *       正常探测、恢复收敛（PRESENT、error 清零），无残留锁死。</li>
 * </ul>
 *
 * <p>取证：publication_resource(state/check_error_count/last_checked_at)、
 * execution_event(PUBLICATION_DRIFT_PERMISSION_ALERT 计数)。
 *
 * <p>复原：每方法 TRUNCATE 全表（基座）+ 独立 WireMock 实例。
 *
 * <p><b>EX-28 v1.2 裁定（人工复位制，评审波次3 落地）</b>：
 * {@code PostgresPublicationStore.findDueForDriftCheck} 扫描集仅 IN (PRESENT, MISSING)，
 * 权限恢复后 UNKNOWN 资源<b>不自动回队</b>为既定行为（不再登记缺陷）；恢复路径为
 * runbook 人工执行复位 SQL（{@code UPDATE publication_resource SET state='PRESENT',
 * next_check_at=now() WHERE id=...}）拨回 PRESENT 后恢复巡检。
 */
class Ex28PermissionRestoreIT extends PostgresITBase {

    private static final String HEAD = "head" + "e".repeat(36);
    private static final String REPO = "objwww/mall";
    private static final int PR = 28;
    private static final long REPO_ID = 2028L;
    private static final String CHECK_PROBE =
            "/repos/" + REPO + "/commits/" + HEAD + "/check-runs?per_page=100&page=1";
    private static final String REVIEW_PROBE =
            "/repos/" + REPO + "/pulls/" + PR + "/reviews?per_page=100&page=1";
    private static final String SANITY = "/repos/" + REPO;

    private WireMockServer wiremock;
    private ItHarness harness;
    private ExRepairChain.Published published;

    @BeforeEach
    void setUp() {
        wiremock = new WireMockServer(wireMockConfig().dynamicPort());
        wiremock.start();
        harness = new ItHarness(casDir, wiremock.baseUrl());
        published = ExRepairChain.publishPair(harness, wiremock, "ex28-d1", REPO_ID, REPO, PR, HEAD);
    }

    @AfterEach
    void tearDown() {
        wiremock.stop();
    }

    @Test
    void unknownResourceDoesNotBlockPatrolOfOthers() {
        injectPermissionRevocation();
        stubReviewProbeFound();

        // 权限撤销轮：check → UNKNOWN + 权限告警；review 不受影响照常 PRESENT
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(2);
        assertThat(stateOf("CHECK_RUN")).isEqualTo("UNKNOWN");
        assertThat(permissionAlerts()).isEqualTo(1);
        assertThat(stateOf("REVIEW")).isEqualTo("PRESENT");
        assertThat(adminJdbc.sql(
                "SELECT check_error_count FROM publication_resource WHERE resource_type = 'REVIEW'")
                .query(Integer.class).single()).isZero();
        assertNoDriftNoRepair();

        // 下一轮：UNKNOWN 不在扫描集（现行语义），其余资源巡检不因此被拖死
        ExRepairChain.expediteChecks("REVIEW");
        ExRepairChain.expediteChecks("CHECK_RUN"); // 即使拨回到期也不入扫描集
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1); // 只处理 REVIEW
        assertThat(stateOf("CHECK_RUN")).isEqualTo("UNKNOWN");
        assertThat(permissionAlerts()).isEqualTo(1); // 不重复告警
        assertThat(stateOf("REVIEW")).isEqualTo("PRESENT");
        assertNoDriftNoRepair();
    }

    /**
     * EX-28 v1.2 裁定（人工复位制）：权限恢复后 UNKNOWN 资源<b>不自动回队</b>（M1
     * "UNKNOWN 不在巡检扫描集"既定行为维持）；由 runbook 人工 SQL 复位拨回 PRESENT 后
     * 恢复巡检。复位 SQL 原文：{@code UPDATE publication_resource SET state='PRESENT',
     * next_check_at=now() WHERE id=...}
     */
    @Test
    void permissionRestoredResourceReconvergesToPatrol() {
        injectPermissionRevocation();
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(2);
        assertThat(stateOf("CHECK_RUN")).isEqualTo("UNKNOWN");
        assertThat(permissionAlerts()).isEqualTo(1);

        // 权限恢复：探针重新可见（单命中）+ sanity 可达
        wiremock.stubFor(get(urlEqualTo(CHECK_PROBE))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"check_runs\":[{\"id\":1001,\"external_id\":\""
                                + published.checkOperationId() + "\"}]}")));
        wiremock.stubFor(get(urlEqualTo(SANITY))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"full_name\":\"" + REPO + "\"}")));
        ExRepairChain.expediteChecks("CHECK_RUN");

        // (a) 裁定钉死：未人工复位前 UNKNOWN 仍不在巡检面——一轮巡检零处理、状态仍 UNKNOWN
        assertThat(harness.newDriftReconciler().runOnce()).isZero();
        assertThat(stateOf("CHECK_RUN")).isEqualTo("UNKNOWN");
        assertThat(permissionAlerts()).isEqualTo(1); // 不重复告警

        // (b) runbook 人工复位（拨回 PRESENT）后：下一轮巡检正常探测、恢复收敛
        adminJdbc.sql("UPDATE publication_resource SET state='PRESENT', next_check_at=now()"
                        + " WHERE id = :id")
                .param("id", published.checkResourceId()).update();
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(stateOf("CHECK_RUN")).isEqualTo("PRESENT"); // FOUND → 保持 PRESENT
        assertThat(adminJdbc.sql(
                "SELECT check_error_count FROM publication_resource WHERE resource_type = 'CHECK_RUN'")
                .query(Integer.class).single()).isZero();
        assertNoDriftNoRepair();
    }

    /** 权限撤销注入：对象探针空列表（404 语义）+ sanity 读 404（不可区分"不存在"与"无权限"） */
    private void injectPermissionRevocation() {
        wiremock.stubFor(get(urlEqualTo(CHECK_PROBE))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"check_runs\":[]}")));
        wiremock.stubFor(get(urlEqualTo(SANITY))
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Not Found\"}")));
        // review 探针保持 FOUND（对照组）：权限撤销不影响本方法的 review 断言面
        stubReviewProbeFound();
    }

    private void stubReviewProbeFound() {
        wiremock.stubFor(get(urlEqualTo(REVIEW_PROBE))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(ExRepairChain.reviewListJson(
                                ExRepairChain.zeroFindingsReviewBody(published.reviewOperationId())))));
    }

    private String stateOf(String resourceType) {
        return adminJdbc.sql("SELECT state FROM publication_resource WHERE resource_type = :type")
                .param("type", resourceType).query(String.class).single();
    }

    private long permissionAlerts() {
        return adminJdbc.sql(
                "SELECT count(*) FROM execution_event"
                        + " WHERE event_type = 'PUBLICATION_DRIFT_PERMISSION_ALERT'")
                .query(Long.class).single();
    }

    private void assertNoDriftNoRepair() {
        assertThat(adminJdbc.sql(
                "SELECT count(*) FROM execution_event WHERE event_type = 'PUBLICATION_DRIFT_DETECTED'")
                .query(Long.class).single()).isZero();
        assertThat(count("repair_request")).isZero();
    }
}
