package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.shared.RunState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-24（M2 方案 §11/L4，回指 I22/C-4）：repair 命令执行瞬间 revision 恰被 SUPERSEDED（竞态）。
 *
 * <p>注入：CHECK_RUN 漂移 MISSING → AUTO repair 铸单 DISPATCHED（命令 epoch 1、PENDING）；
 * 执行前一刻 synchronize 新 head 换届（epoch 2、原 Run SUPERSEDED）——确定性复现
 * "铸单后、领取前"的竞态窗口（真并发由 CT-21 族覆盖，本例钉判定链）。
 *
 * <p>断言：claimer 领取后 epoch fence（STALE_EPOCH）拒绝 → 命令 SUPERSEDED，全程零触网
 * （连 probe-first 读探针都未发出）；RepairOutcomeProjector 收敛 request → EXPIRED +
 * REPAIR_EXPIRED 事件；control 侧第二轮 Planner 把零 Step REPAIR Run 收口 FAILED；
 * 既有资源行不被误改（CHECK_RUN 保持 MISSING、REVIEW 保持 PRESENT）。
 *
 * <p>取证：outbox_command(state/last_error_code)、repair_request(state/last_error)、
 * execution_event(REPAIR_EXPIRED)、review_run(REPAIR)、WireMock serve event 计数。
 *
 * <p>复原：每方法 TRUNCATE 全表（基座）+ 独立 WireMock 实例。
 */
class Ex24RepairSupersedeRaceIT extends PostgresITBase {

    private static final String HEAD1 = "head" + "9".repeat(36);
    private static final String HEAD2 = "head" + "a".repeat(36);
    private static final String REPO = "objwww/mall";
    private static final int PR = 24;
    private static final long REPO_ID = 2024L;

    private WireMockServer wiremock;
    private ItHarness harness;
    private ExRepairChain.Published published;
    private UUID repairOp;

    @BeforeEach
    void setUp() {
        wiremock = new WireMockServer(wireMockConfig().dynamicPort());
        wiremock.start();
        harness = new ItHarness(casDir, wiremock.baseUrl());

        published = ExRepairChain.publishPair(harness, wiremock, "ex24-d1", REPO_ID, REPO, PR, HEAD1);
        repairOp = ExRepairChain.driftCheckToMissingAndPlan(harness, wiremock, REPO, HEAD1);
        assertThat(adminJdbc.sql("SELECT state FROM repair_request").query(String.class).single())
                .isEqualTo("DISPATCHED");
    }

    @AfterEach
    void tearDown() {
        wiremock.stop();
    }

    @Test
    void repairCommandMintedThenRevisionSupersededIsFenced() {
        // 竞态成形：repair 命令已铸未领，push 新 commit 换届（epoch 2）
        int serveEventsBefore = wiremock.getAllServeEvents().size();
        harness.dispatchOpened(ItHarness.prEvent("ex24-d2", REPO_ID, REPO, PR, HEAD2, "synchronize"),
                ItTarballs.singleFile("src/A.java", "class A { int b = 2; }\n"), "diff-2");
        assertThat(harness.runRepo.findById(published.reviewRunId()).orElseThrow().getState())
                .isEqualTo(RunState.SUPERSEDED);

        harness.newClaimer().runOnce();

        // fence 拒绝：命令 SUPERSEDED（STALE_EPOCH），领取全程零触网（读探针都未发出）
        assertThat(wiremock.getAllServeEvents()).hasSize(serveEventsBefore);
        assertThat(adminJdbc.sql(
                "SELECT state || ':' || last_error_code FROM outbox_command WHERE operation_id = :id")
                .param("id", repairOp).query(String.class).single())
                .isEqualTo("SUPERSEDED:STALE_EPOCH");

        // Projector 收敛：request → EXPIRED + REPAIR_EXPIRED 事件
        assertThat(harness.newRepairOutcomeProjector().runOnce()).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT state || ':' || last_error FROM repair_request")
                .query(String.class).single()).isEqualTo("EXPIRED:COMMAND_SUPERSEDED");
        assertThat(adminJdbc.sql(
                "SELECT count(*) FROM execution_event WHERE event_type = 'REPAIR_EXPIRED'")
                .query(Long.class).single()).isEqualTo(1);

        // control 侧第二轮 Planner：零 Step REPAIR Run 收口 FAILED
        assertThat(harness.newRepairPlanner().runOnce()).isEqualTo(1);
        assertThat(adminJdbc.sql("""
                SELECT r.state FROM review_run r JOIN repair_request rr ON rr.repair_run_id = r.id
                """).query(String.class).single()).isEqualTo("FAILED");

        // 资源行不被误改：check 保持 MISSING（不冒充已修），review 不受影响
        assertThat(adminJdbc.sql("SELECT state FROM publication_resource ORDER BY created_at")
                .query(String.class).list()).containsExactly("MISSING", "PRESENT");
    }
}
