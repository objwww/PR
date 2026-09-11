package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresCanaryDecisionLogRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresConfigBundleRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.release.domain.model.CanaryDecision;
import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.control.release.domain.repository.CanaryDecisionLogRepository;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.application.CanaryRouter;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * M5-10 Canary 路由四列真 PG 集成（落码方案 §M5-10④ IT 面；命名 *IT 由 failsafe
 * verify 执行，本机无 docker 自动跳过，195 真跑补证据）：
 * <ul>
 *   <li>insertRouted 落 V25 路由四列（engine/config_digest/stickiness_key/canary_bucket）；</li>
 *   <li>老 Run 固定旧 digest——pointer 移动/回滚不改历史行，只影响新 Run；</li>
 *   <li>uq_rca_run_active_incident 迁 (incident_id, engine) 粒度：同引擎双活跃仍 23505，
 *       异引擎活跃共存（NATIVE 影子对照的物理前提）；</li>
 *   <li>M6-01 案②：REPORTING 占活跃槽——索引谓词含 REPORTING（V12 扩集），并发
 *       铸造被索引拒绝；出活跃集后槽位释放。</li>
 *   <li>BA-52：生产铸造点（castRunAndTask）顺序 = route() 决策行先落、insertRouted()
 *       run 行后落——同事务原子对；run_id FK 即时检查（NOT DEFERRABLE）即每次新
 *       run 铸造必 23503，投影整组重试后 DEAD_LETTER（195 真栈 E2E-AM6-00 首跑
 *       实证）。V31 改 DEFERRABLE INITIALLY DEFERRED（提交点检查），本测试以生产
 *       顺序钉死原子对语义。</li>
 * </ul>
 */
class PostgresCanaryRoutingIT extends PostgresITBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RcaRunRepository runs;
    private IncidentRepository incidents;
    private ConfigBundleRepository bundles;
    private CanaryDecisionLogRepository decisions;

    @BeforeEach
    void setUp() {
        JdbcClient jdbc = JdbcClient.create(controlDataSource());
        runs = new PostgresRcaRunRepository(jdbc);
        incidents = new PostgresIncidentRepository(jdbc);
        bundles = new PostgresConfigBundleRepository(controlDataSource());
        decisions = new PostgresCanaryDecisionLogRepository(jdbc);
        // config 族两表自清（BA-41：基座 TRUNCATE 清单刻意排除 V24 种子行依赖面）；
        // 删后必须回种 id=1（BA-42②：激活 CAS 的 WHERE id=1 依赖种子行在场，
        // 195 真 PG 实证：无行则 activate 恒 false）
        adminJdbc.sql("DELETE FROM canary_route_decision").update();
        adminJdbc.sql("DELETE FROM config_bundle_active").update();
        adminJdbc.sql("DELETE FROM config_bundle").update();
        adminJdbc.sql("INSERT INTO config_bundle_active (id) VALUES (1)").update();
    }

    // ----------------------------------------------- 路由四列落行 + 决策审计追加面

    @Test
    void insertRoutedPersistsRoutingColumnsAndDecisionAudit() {
        UUID incidentId = insertIncident("routed-" + UUID.randomUUID());
        Digest digest = Digest.sha256Of("bundle-v1");
        RcaRun run = run(incidentId);
        RcaRunRouting routing = new RcaRunRouting(
                RcaEngine.NATIVE, digest, "alertname=higherror|service=checkout", 37,
                CanaryDecision.BUCKETED_NATIVE.name());

        runs.insertRouted(run, routing);

        // BA-41：SimplePropertyRowMapper 不支持 Map.class——显式行映射保持断言形状
        Map<String, Object> row = adminJdbc.sql("""
                SELECT engine, config_digest, stickiness_key, canary_bucket
                  FROM rca_run WHERE id = :id
                """).param("id", run.id())
                .query((rs, i) -> Map.<String, Object>of(
                        "engine", rs.getString("engine"),
                        "config_digest", rs.getString("config_digest"),
                        "stickiness_key", rs.getString("stickiness_key"),
                        "canary_bucket", rs.getInt("canary_bucket")))
                .single();
        assertThat(row.get("engine")).isEqualTo("NATIVE");
        assertThat(row.get("config_digest")).isEqualTo(digest.hex());
        assertThat(row.get("stickiness_key")).isEqualTo("alertname=higherror|service=checkout");
        assertThat(((Number) row.get("canary_bucket")).intValue()).isEqualTo(37);

        // 审计面：NATIVE 实跑决策计入爆炸半径重数，HOLMES 落桶不计
        //（BA-43：决策行 FK 钉 rca_run——第二行同 run 落桶 HOLMES，计数仍只看 NATIVE）
        decisions.append(new CanaryDecisionLogRepository.DecisionRow(
                run.id(), "alertname=higherror|service=checkout", 37, 5, digest,
                CanaryDecision.BUCKETED_NATIVE.name(), Instant.now()));
        decisions.append(new CanaryDecisionLogRepository.DecisionRow(
                run.id(), "alertname=lowerror|service=cart", 82, 5, digest,
                CanaryDecision.BUCKETED_HOLMES.name(), Instant.now()));
        assertThat(decisions.countNativeDecisions()).isEqualTo(1);
        assertThat(count("canary_route_decision")).isEqualTo(2);
    }

    // ----------------------------------------------- 老 Run 固定旧 digest（回滚只影响新 Run）

    @Test
    void pointerMoveDoesNotRewriteExistingRunDigest() {
        Instant now = Instant.now();
        Digest v1 = publish(Map.of("app", Map.of("model", Map.of("route", "holmes"))), now);
        Digest v2 = publish(Map.of("app", Map.of("model", Map.of("route", "native"))), now);
        grantPassQualification(v1);
        assertThat(bundles.activateQualified(v1, 0L, "it", now)).isTrue();

        UUID incidentId = insertIncident("digest-" + UUID.randomUUID());
        RcaRun oldRun = run(incidentId);
        runs.insertRouted(oldRun, new RcaRunRouting(
                RcaEngine.NATIVE, v1, "alertname=higherror|service=checkout", 12,
                CanaryDecision.BUCKETED_NATIVE.name()));

        // 指针移到 v2（发布新版本 = 回滚语义：activate 到旧 digest 同一路径）；
        // expectedActiveRevision = 在位指针（v1，revision 1）的 revision
        grantPassQualification(v2);
        assertThat(bundles.activateQualified(v2, 1L, "it", now)).isTrue();

        assertThat(adminJdbc.sql(
                        "SELECT config_digest FROM rca_run WHERE id = :id")
                .param("id", oldRun.id())
                .query((rs, i) -> rs.getString("config_digest")).single())
                .as("老 Run 固定铸造时 digest——指针移动不改历史行")
                .isEqualTo(v1.hex());

        // 新 Run 读新指针（V25 ck 约束：NATIVE 必带 digest）。
        // BA-43：独立 incident——同 incident+engine 双活跃 run 本就被
        // uq_rca_run_active_incident 拒绝（第三案专门验证），此处不再借用同 incident
        UUID incident2 = insertIncident("digest-new-" + UUID.randomUUID());
        RcaRun newRun = run(incident2);
        runs.insertRouted(newRun, new RcaRunRouting(
                RcaEngine.NATIVE, v2, "alertname=higherror|service=checkout", 12,
                CanaryDecision.BUCKETED_NATIVE.name()));
        assertThat(adminJdbc.sql(
                        "SELECT config_digest FROM rca_run WHERE id = :id")
                .param("id", newRun.id())
                .query((rs, i) -> rs.getString("config_digest")).single())
                .as("回滚/更新只影响新 Run")
                .isEqualTo(v2.hex());
    }

    // ----------------------------------------------- uq 索引 (incident_id, engine) 粒度

    @Test
    void activeUniqueIndexRejectsSameEngineAndAllowsOppositeEngine() {
        UUID incidentId = insertIncident("uq-engine-" + UUID.randomUUID());

        runs.insertRouted(run(incidentId), RcaRunRouting.holmes(
                null, null, null, CanaryDecision.BUCKETED_HOLMES.name()));
        // 异引擎活跃共存（NATIVE 影子对照的物理前提——部分索引按 engine 分槽）
        runs.insertRouted(run(incidentId), new RcaRunRouting(
                RcaEngine.NATIVE, Digest.sha256Of("bundle-v1"),
                "alertname=higherror|service=checkout", 12,
                CanaryDecision.BUCKETED_NATIVE.name()));

        // 同引擎双活跃仍 23505（HOLMES 槽内 INV-AM1-2 语义原样保留）
        assertThatExceptionOfType(DuplicateKeyException.class).isThrownBy(
                () -> runs.insertRouted(run(incidentId), RcaRunRouting.holmes(
                        null, null, null, CanaryDecision.BUCKETED_HOLMES.name())));

        assertThat(count("rca_run")).isEqualTo(2);
    }

    // ------------------------- M6-01 案②：REPORTING 占活跃槽（索引谓词含 REPORTING）

    @Test
    void reportingRunOccupiesActiveSlotAndBlocksConcurrentMint() {
        UUID incidentId = insertIncident("reporting-" + UUID.randomUUID());
        RcaRun first = run(incidentId);
        runs.insertRouted(first, RcaRunRouting.holmes(
                null, null, null, CanaryDecision.BUCKETED_HOLMES.name()));
        // 真实推进形态：调查任务全部终态后经 advance 入组装期（C-72：REPORTING 属活跃集）
        runs.update(inState(first, RcaRunState.REPORTING));

        // REPORTING 期间并发铸造同 (incident, engine) → 23505——活跃索引谓词含
        // REPORTING（V12 扩集 + BA-43 重建保留；M6-01 案② 回归钉）
        assertThatExceptionOfType(DuplicateKeyException.class).isThrownBy(
                () -> runs.insertRouted(run(incidentId), RcaRunRouting.holmes(
                        null, null, null, CanaryDecision.BUCKETED_HOLMES.name())));

        // 异引擎分槽不受阻（NATIVE 候选与 HOLMES 主路径并存的物理前提）
        runs.insertRouted(run(incidentId), new RcaRunRouting(
                RcaEngine.NATIVE, Digest.sha256Of("bundle-v1"),
                "alertname=higherror|service=checkout", 12,
                CanaryDecision.BUCKETED_NATIVE.name()));

        // 出活跃集（SUCCEEDED）后槽位释放：新 HOLMES run 可铸（先证能拒、再证能过）
        runs.update(inState(first, RcaRunState.SUCCEEDED));
        runs.insertRouted(run(incidentId), RcaRunRouting.holmes(
                null, null, null, CanaryDecision.BUCKETED_HOLMES.name()));

        assertThat(count("rca_run")).isEqualTo(3);
    }

    // --------------------- BA-52：决策行先落/run 行后落（生产铸造点同事务原子对）

    @Test
    void decisionAppendBeforeRunInsertCommitsAsOneTransaction() {
        // 生产铸造点顺序（IncidentProjector / RcaRunOrchestrator castRunAndTask）：
        // canaryRouter.route() 内决策行先 append，runs.insertRouted() 的 run 行后落，
        // 两者同事务。BA-52：run_id FK 即时检查使该顺序必然 23503（每次新 run 铸造
        // 必炸、投影整组 DEAD_LETTER）——V31 改 DEFERRABLE INITIALLY DEFERRED，
        // 提交点检查恰与"原子对"语义对齐。本测试按生产顺序（先 route 后 insertRouted、
        // 同 runId）钉死回归。
        Digest digest = publish(Map.of("app", Map.of("model", Map.of("route", "holmes"))),
                Instant.now());
        grantPassQualification(digest);
        assertThat(bundles.activateQualified(digest, 0L, "it", Instant.now())).isTrue();
        CanaryRouter router = new CanaryRouter(bundles, decisions, false, Instant::now);
        UUID incidentId = insertIncident("fk-defer-" + UUID.randomUUID());
        String key = "alertname=higherror|service=checkout";

        controlTx.executeWithoutResult(tx -> {
            UUID runId = UUID.randomUUID();
            RcaRunRouting routing = router.route(runId, key, key);
            RcaRun run = new RcaRun(runId, incidentId, 0, RunTrigger.INITIAL,
                    RcaRunState.QUEUED, Digest.sha256Of("run-" + runId),
                    Instant.now(), Instant.now(), null, null, null);
            runs.insertRouted(run, routing);
        });

        assertThat(count("rca_run")).isEqualTo(1);
        assertThat(count("canary_route_decision")).isEqualTo(1);
    }

    // --------------------- BA-60：C-77 零铸造的审计归属面（run_id 可空，V35）

    @Test
    void holmesWillingDecisionRowCommitsWithNullRunId() {
        // M6-07 C-77：HOLMES 意愿路由=决策行照记、不铸 run。两铸造点先预生成 runId
        // 传入 route()——若决策行照抄该 id 而 run 行永不落库，V31 deferred FK 在提交点
        // 必 23503（幽灵引用，BA-53 同型；本地假件无 FK 面全绿不可见）。修复=V35 摘
        // NOT NULL + 路由器对非 NATIVE 出路落 NULL：照记保留、归属留空、提交无幽灵。
        Digest digest = publish(Map.of("canary", Map.of("percent", 0)), Instant.now());
        grantPassQualification(digest);
        assertThat(bundles.activateQualified(digest, 0L, "it", Instant.now())).isTrue();
        CanaryRouter router = new CanaryRouter(bundles, decisions, true, Instant::now);
        String key = "alertname=higherror|service=am607-p0";

        controlTx.executeWithoutResult(tx -> {
            UUID runId = UUID.randomUUID();
            RcaRunRouting routing = router.route(runId, key, key);
            assertThat(routing.engine()).isEqualTo(RcaEngine.HOLMES);
            assertThat(routing.decision()).isEqualTo(CanaryDecision.BUCKETED_HOLMES.name());
            // C-77 守卫早退形态：run 行不落（对照上一案 NATIVE 原子对）
        });

        assertThat(count("rca_run")).as("零 run 铸造（C-77）").isZero();
        assertThat(adminJdbc.sql("""
                        SELECT count(*) FROM canary_route_decision
                         WHERE stickiness_key = :k AND run_id IS NULL
                        """)
                .param("k", key + ":" + key)
                .query(Long.class).single())
                .as("决策行照记且 run_id 归属留空（提交点无幽灵引用）")
                .isEqualTo(1L);
    }

    /** 活跃态迁移行（同 withRunState 语义：updatedAt=now，出活跃集补 completedAt） */
    private static RcaRun inState(RcaRun r, RcaRunState state) {
        Instant now = Instant.now();
        return new RcaRun(r.id(), r.incidentId(), r.generation(), r.trigger(), state,
                r.investigationHash(), r.createdAt(), now, r.startedAt(),
                state.isActive() ? null : now, null);
    }

    // ------------------------------------------------------------------ 种子

    /** 发布一版 bundle（policy_version 必填；canonical digest 在 of() 内铸成）。 */
    private Digest publish(Map<String, Object> content, Instant now) {
        Map<String, Object> full = new LinkedHashMap<>(content);
        full.put("policy_version", "policy-2026-09");
        ConfigBundle bundle = ConfigBundle.of(full, "it", now);
        assertThat(bundles.insert(bundle)).isTrue();
        return bundle.bundleDigest();
    }

    private RcaRun run(UUID incidentId) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        return new RcaRun(id, incidentId, 0, RunTrigger.INITIAL, RcaRunState.QUEUED,
                Digest.sha256Of("run-" + id), now.minus(Duration.ofMinutes(4)), now,
                null, null, null);
    }

    private UUID insertIncident(String key) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        incidents.insert(new Incident(id, "alertname=HighErrorRate|service=" + key,
                IncidentStatus.FIRING, 0, now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(5)),
                null, null, null, 0, 0, 0, null,
                now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(5)), now, now));
        return id;
    }
}
