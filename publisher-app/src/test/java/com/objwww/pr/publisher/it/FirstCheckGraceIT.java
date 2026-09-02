package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.publisher.infrastructure.persistence.PostgresExecutionEventAppender;
import com.objwww.pr.publisher.infrastructure.persistence.PostgresPublicationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-25（M2 收尾修复）：新 CONFIRMED 资源的首查宽限——insertResource 时
 * {@code next_check_at = now() + first-check-grace}，不再吃列默认值 {@code now()}。
 *
 * <p>被测件：宽限 1h 的真实 {@link PostgresPublicationStore}（sabotage 替换线束默认的
 * 零宽限实例），confirm 走真实 T3-B 链路落资源行。两个场景：
 * <ul>
 *   <li>{@link #newResourceIsNotScannedWithinGraceWindow}——宽限窗内 DriftReconciler
 *       跑一轮零处理：last_checked_at 仍 NULL、state 不变、next_check_at 在宽限之外；</li>
 *   <li>{@link #forcedDueResourceIsScannedNormally}——next_check_at 被拨到过去
 *       （deploy m2_force_drift_due 同形的 force tick）后正常被扫描，PRESENT 路径
 *       落 last_checked_at 并按正常巡检间隔重排。</li>
 * </ul>
 *
 * <p>复原：每方法 TRUNCATE 全表（基座）+ 独立 WireMock 实例。
 */
class FirstCheckGraceIT extends PostgresITBase {

    private static final Duration GRACE = Duration.ofHours(1);
    private static final String HEAD_SHA = "head" + "f".repeat(36);
    private static final String REPO = "objwww/mall";
    private static final int PR = 55;

    private WireMockServer wiremock;
    private ItHarness harness;

    @BeforeEach
    void setUp() {
        wiremock = new WireMockServer(wireMockConfig().dynamicPort());
        wiremock.start();
        harness = new ItHarness(casDir, wiremock.baseUrl());

        harness.dispatchOpened(ItHarness.prEvent("tb25-d1", 2055L, REPO, PR, HEAD_SHA, "opened"),
                ItTarballs.singleFile("src/A.java", "class A {}\n"), "diff");
        harness.modelClient.enqueueContent("[]");
        harness.newWorker("worker-1").runOnce();
        wiremock.stubFor(post(urlEqualTo("/repos/" + REPO + "/check-runs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1001,\"html_url\":\"http://x/check/1001\"}")));
        wiremock.stubFor(post(urlEqualTo("/repos/" + REPO + "/pulls/" + PR + "/reviews"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":2002,\"html_url\":\"http://x/review/2002\"}")));

        // TB-25 被测件：首查宽限 1h 的真实 store（替换线束刻意零宽限的默认实例）
        harness.sabotageStore(new PostgresPublicationStore(publisherJdbc, publisherTx,
                new PostgresExecutionEventAppender(publisherJdbc, OM), GRACE));
        harness.newClaimer().runOnce(); // T3-B confirm → insertResource 落 next_check_at=now()+1h

        assertThat(count("publication_resource")).isEqualTo(2); // CHECK_RUN + REVIEW
    }

    @AfterEach
    void tearDown() {
        wiremock.stop();
    }

    @Test
    void newResourceIsNotScannedWithinGraceWindow() {
        // 宽限窗内：一轮巡检零处理，观测列不动、状态不变
        assertThat(harness.newDriftReconciler().runOnce()).isZero();
        assertThat(adminJdbc.sql("SELECT count(*) FROM publication_resource"
                        + " WHERE state = 'PRESENT' AND last_checked_at IS NULL")
                .query(Long.class).single()).isEqualTo(2);
        // next_check_at 确实被推到宽限之外（> now()+50min），不再吃列默认值 now()
        assertThat(adminJdbc.sql("SELECT count(*) FROM publication_resource"
                        + " WHERE next_check_at > now() + interval '50 minutes'")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void forcedDueResourceIsScannedNormally() {
        // 探针 FOUND 注入（EX14 同形态：check 单命中 + review 零 finding 正文逐字节对齐）
        UUID checkOp = adminJdbc.sql(
                        "SELECT operation_id FROM outbox_command WHERE command_type = 'CREATE_CHECK'")
                .query(UUID.class).single();
        wiremock.stubFor(get(urlEqualTo("/repos/" + REPO + "/commits/" + HEAD_SHA
                        + "/check-runs?per_page=100&page=1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"check_runs\":[{\"id\":1001,\"external_id\":\""
                                + checkOp + "\"}]}")));
        UUID reviewOp = adminJdbc.sql(
                        "SELECT operation_id FROM outbox_command WHERE command_type = 'PUBLISH_REVIEW'")
                .query(UUID.class).single();
        wiremock.stubFor(get(urlEqualTo("/repos/" + REPO + "/pulls/" + PR + "/reviews"
                        + "?per_page=100&page=1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(ExRepairChain.reviewListJson(
                                ExRepairChain.zeroFindingsReviewBody(reviewOp)))));

        // 模拟 force tick：next_check_at 拨到过去（deploy m2_force_drift_due 同形）
        adminJdbc.sql("UPDATE publication_resource SET next_check_at = now() - interval '1 second'")
                .update();

        // 到期即正常扫描：两条都走 PRESENT 路径——last_checked_at 落时、按正常间隔（60min）重排
        assertThat(harness.newDriftReconciler().runOnce()).isEqualTo(2);
        assertThat(adminJdbc.sql("SELECT count(*) FROM publication_resource"
                        + " WHERE state = 'PRESENT' AND last_checked_at IS NOT NULL"
                        + " AND check_error_count = 0")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(adminJdbc.sql("SELECT count(*) FROM publication_resource"
                        + " WHERE next_check_at > now() + interval '50 minutes'")
                .query(Long.class).single()).isEqualTo(2);
    }
}
