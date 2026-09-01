package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-26（M2 方案 §11/L4，回指 §4.6）：5xx/超时/连接中断持续注入的巡检面行为。
 *
 * <p>注入：CHECK_RUN 探针持续 500（5 轮）；连接重置（CONNECTION_RESET_BY_PEER）与
 * 响应超时（stub 固定延迟 6s > 适配器 5s 超时）各一轮。REVIEW 资源移出巡检面作隔离。
 *
 * <p>断言：三种失败一律归探测失败（UNKNOWN 语义），非限流指数退避（30→60→120→240→480s
 * 逐轮倍增）；check_error_count 单调递增；连 3 次起 RECONCILER_DEGRADED 族告警落账；
 * 资源状态全程不误改（保持 PRESENT，绝不标 MISSING）；零漂移事件、零 repair_request。
 *
 * <p>取证：publication_resource(state/check_error_count/next_check_at-now() 实测退避秒数)、
 * execution_event(RECONCILER_DEGRADED / PUBLICATION_DRIFT_DETECTED)、repair_request 零行。
 *
 * <p>复原：每方法 TRUNCATE 全表（基座）+ 独立 WireMock 实例。
 *
 * <p>备注：漂移巡检面按 §4.6 设计以"告警 + 退避持续巡检"为终态表达，无 MANUAL 熔断
 * （熔断终态在写路径 EX-01 与崩溃收敛面 EX-04 钉死）；本类钉"持续失败不冒充事实"半面。
 */
class Ex26ProbeFailureBudgetIT extends PostgresITBase {

    private static final String HEAD = "head" + "c".repeat(36);
    private static final String REPO = "objwww/mall";
    private static final int PR = 26;
    private static final long REPO_ID = 2026L;
    private static final String CHECK_PROBE =
            "/repos/" + REPO + "/commits/" + HEAD + "/check-runs?per_page=100&page=1";

    private WireMockServer wiremock;
    private ItHarness harness;
    private UUID checkResourceId;

    @BeforeEach
    void setUp() {
        wiremock = new WireMockServer(wireMockConfig().dynamicPort());
        wiremock.start();
        harness = new ItHarness(casDir, wiremock.baseUrl());
        checkResourceId = ExRepairChain
                .publishPair(harness, wiremock, "ex26-d1", REPO_ID, REPO, PR, HEAD)
                .checkResourceId();
        ExRepairChain.deferChecks("REVIEW"); // 隔离对照组，只留 CHECK_RUN 在巡检面
    }

    @AfterEach
    void tearDown() {
        wiremock.stop();
    }

    @Test
    void persistent5xxBacksOffExponentiallyAndDegradesWithoutStateChange() {
        wiremock.stubFor(get(urlEqualTo(CHECK_PROBE)).willReturn(aResponse().withStatus(500)));

        // 非限流指数退避（RetryBackoff：30s 起倍增，§4.6）；逐轮对拍实测排期间隔
        long[] expectedDelaysSec = {30, 60, 120, 240, 480};
        for (int round = 1; round <= 5; round++) {
            assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
            assertThat(stateOfCheck()).isEqualTo("PRESENT"); // 5xx 状态不动
            assertThat(errorCountOfCheck()).isEqualTo(round);
            double scheduledInSec = adminJdbc.sql(
                    "SELECT EXTRACT(EPOCH FROM (next_check_at - now()))::float8"
                            + " FROM publication_resource WHERE id = :id")
                    .param("id", checkResourceId).query(Double.class).single();
            assertThat(scheduledInSec)
                    .isBetween(expectedDelaysSec[round - 1] - 10.0, expectedDelaysSec[round - 1] + 10.0);
            ExRepairChain.expediteChecks("CHECK_RUN"); // 测试动作：拨回排期驱动下一轮
        }

        // 阈值（连 3 次）起 RECONCILER_DEGRADED 族告警已落账；探测失败不冒充事实
        assertThat(adminJdbc.sql(
                "SELECT count(*) FROM execution_event WHERE event_type = 'RECONCILER_DEGRADED'")
                .query(Long.class).single()).isGreaterThanOrEqualTo(1);
        assertThat(adminJdbc.sql(
                "SELECT count(*) FROM execution_event WHERE event_type = 'PUBLICATION_DRIFT_DETECTED'")
                .query(Long.class).single()).isZero();
        assertThat(count("repair_request")).isZero();
        assertThat(stateOfCheck()).isEqualTo("PRESENT");
    }

    @Test
    void timeoutAndConnectionBreakAreUnknownNotMissing() {
        // 第 1 轮：连接中断（对端 RST）
        wiremock.stubFor(get(urlEqualTo(CHECK_PROBE))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(errorCountOfCheck()).isEqualTo(1);
        assertThat(stateOfCheck()).isEqualTo("PRESENT");

        // 第 2 轮：响应超时（stub 6s 延迟 > 适配器 5s 超时）
        ExRepairChain.expediteChecks("CHECK_RUN");
        wiremock.stubFor(get(urlEqualTo(CHECK_PROBE))
                .willReturn(aResponse().withStatus(200).withFixedDelay(6000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"check_runs\":[]}")));
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(1);
        assertThat(errorCountOfCheck()).isEqualTo(2);
        assertThat(stateOfCheck()).isEqualTo("PRESENT");

        // 未达降级阈值（3）：零告警、零漂移事件、零修复单、状态不误改
        assertThat(adminJdbc.sql(
                "SELECT count(*) FROM execution_event WHERE event_type = 'RECONCILER_DEGRADED'")
                .query(Long.class).single()).isZero();
        assertThat(adminJdbc.sql(
                "SELECT count(*) FROM execution_event WHERE event_type = 'PUBLICATION_DRIFT_DETECTED'")
                .query(Long.class).single()).isZero();
        assertThat(count("repair_request")).isZero();
    }

    private String stateOfCheck() {
        return adminJdbc.sql("SELECT state FROM publication_resource WHERE id = :id")
                .param("id", checkResourceId).query(String.class).single();
    }

    private int errorCountOfCheck() {
        return adminJdbc.sql("SELECT check_error_count FROM publication_resource WHERE id = :id")
                .param("id", checkResourceId).query(Integer.class).single();
    }
}
