package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ST-35（M2 方案 §11/L3，回指 §4.3 铸造点"不铸单的情形" + §4.6 分流；与 EX-14/EX-17
 * 互补——它们断言状态/告警面，本类钉死四种非事实性 MISSING 注入下 repair_request 恒零行）。
 * 场景（注入）：CHECK_RUN 探针分别注入 ① 404 + sanity 失败 ② 普通 403（无限流头）
 * ③ 5xx ④ 429（无 Retry-After 头）。
 * 预期断言：四种注入均不产生 repair_request、无 PUBLICATION_DRIFT_DETECTED、outbox 命令
 * 状态不变；资源状态不误改——①② UNKNOWN + 权限告警（不进退避计数），③④ 保持 PRESENT +
 * check_error_count 退避（429 退避下限 60s）。
 * 取证：repair_request 零行 + publication_resource.state/check_error_count/next_check_at +
 * execution_event 告警计数。
 */
class St35NoRepairOnProbeFailureIT extends PostgresITBase {

    private static final String REPO = "objwww/mall";
    private static final int PR = 35;
    private static final long REPOSITORY_ID = 2035L;
    private static final String HEAD = "head" + "8".repeat(36);
    private static final String CHECK_PROBE_URL =
            "/repos/" + REPO + "/commits/" + HEAD + "/check-runs?per_page=100&page=1";
    private static final String SANITY_URL = "/repos/" + REPO;

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

    /** 完整发布一轮 + 只让 CHECK_RUN 到期（REVIEW 排除在巡检面外，隔离单资源注入） */
    private void publishAndMakeCheckDue(String deliveryId) {
        harness.dispatchOpened(ItHarness.prEvent(deliveryId, REPOSITORY_ID, REPO, PR, HEAD, "opened"),
                ItTarballs.singleFile("src/A.java", "class A {}\n"), "diff");
        harness.modelClient.enqueueContent("[]");
        harness.newWorker("worker-1").runOnce();
        wiremock.stubFor(post(urlEqualTo("/repos/" + REPO + "/check-runs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1001,\"html_url\":\"http://x/check/1001\"}")));
        wiremock.stubFor(post(urlEqualTo("/repos/" + REPO + "/pulls/" + PR + "/reviews"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":2001,\"html_url\":\"http://x/review/2001\"}")));
        harness.newClaimer().runOnce();
        adminJdbc.sql("UPDATE publication_resource SET next_check_at=CASE WHEN resource_type='CHECK_RUN' "
                        + "THEN now()-interval '1 second' ELSE now()+interval '1 day' END").update();
    }

    /** 四种注入的公共硬断言：零 repair 单、零漂移事件、outbox 原样（两 CONFIRMED） */
    private void assertNoRepairRequestMinted() {
        assertThat(count("repair_request")).isZero();
        assertThat(adminJdbc.sql("SELECT count(*) FROM execution_event "
                        + "WHERE event_type='PUBLICATION_DRIFT_DETECTED'")
                .query(Long.class).single()).isZero();
        assertThat(adminJdbc.sql("SELECT count(*) FROM execution_event WHERE event_type='REPAIR_REQUESTED'")
                .query(Long.class).single()).isZero();
        assertThat(adminJdbc.sql("SELECT state FROM outbox_command ORDER BY aggregate_sequence")
                .query(String.class).list()).containsExactly("CONFIRMED", "CONFIRMED");
    }

    private String checkResourceState() {
        return adminJdbc.sql("SELECT state FROM publication_resource WHERE resource_type='CHECK_RUN'")
                .query(String.class).single();
    }

    @Test
    void notFoundPlusSanityFailureMintsNoRepair() {
        publishAndMakeCheckDue("st35-d1");
        // 探针 404（列表空）+ sanity 读失败：无法区分"不存在"与"无权限"
        wiremock.stubFor(get(urlEqualTo(CHECK_PROBE_URL))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"check_runs\":[]}")));
        wiremock.stubFor(get(urlEqualTo(SANITY_URL))
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Not Found\"}")));

        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);

        assertNoRepairRequestMinted();
        assertThat(checkResourceState()).isEqualTo("UNKNOWN"); // 绝不标 MISSING
        assertThat(adminJdbc.sql("SELECT count(*) FROM execution_event "
                        + "WHERE event_type='PUBLICATION_DRIFT_PERMISSION_ALERT'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void plainForbiddenMintsNoRepair() {
        publishAndMakeCheckDue("st35-d2");
        // 普通 403（无 x-ratelimit 头）= 权限事实：权限告警路径，不退避不铸单
        wiremock.stubFor(get(urlEqualTo(CHECK_PROBE_URL))
                .willReturn(aResponse().withStatus(403).withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Forbidden\"}")));

        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);

        assertNoRepairRequestMinted();
        assertThat(checkResourceState()).isEqualTo("UNKNOWN");
        assertThat(adminJdbc.sql("SELECT check_error_count FROM publication_resource "
                        + "WHERE resource_type='CHECK_RUN'").query(Integer.class).single())
                .isZero(); // 权限异常不混入退避计数
        assertThat(adminJdbc.sql("SELECT count(*) FROM execution_event "
                        + "WHERE event_type='PUBLICATION_DRIFT_PERMISSION_ALERT'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void serverErrorMintsNoRepair() {
        publishAndMakeCheckDue("st35-d3");
        wiremock.stubFor(get(urlEqualTo(CHECK_PROBE_URL)).willReturn(aResponse().withStatus(500)));

        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);

        assertNoRepairRequestMinted();
        assertThat(checkResourceState()).isEqualTo("PRESENT"); // 状态不误改
        assertThat(adminJdbc.sql("SELECT check_error_count FROM publication_resource "
                        + "WHERE resource_type='CHECK_RUN'").query(Integer.class).single())
                .isEqualTo(1); // 退避计数递增（EX-14 的阈值告警面互补）
    }

    @Test
    void rateLimitedMintsNoRepairAndBacksOff() {
        publishAndMakeCheckDue("st35-d4");
        // 429 无 Retry-After 头 → SecondaryLimitBackoff 下限 60s
        wiremock.stubFor(get(urlEqualTo(CHECK_PROBE_URL)).willReturn(aResponse().withStatus(429)));

        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);

        assertNoRepairRequestMinted();
        assertThat(checkResourceState()).isEqualTo("PRESENT");
        assertThat(adminJdbc.sql("SELECT check_error_count FROM publication_resource "
                        + "WHERE resource_type='CHECK_RUN'").query(Integer.class).single())
                .isEqualTo(1);
        // 退避精确生效：下次巡检不早于 now+50s（留 10s 余量给下限 60s 的执行开销）
        assertThat(adminJdbc.sql("SELECT next_check_at > now() + interval '50 seconds' "
                        + "FROM publication_resource WHERE resource_type='CHECK_RUN'")
                .query(Boolean.class).single()).isTrue();
    }
}
