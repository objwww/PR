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
 *       异引擎活跃共存（NATIVE 影子对照的物理前提）。</li>
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
        assertThat(bundles.activate(v1, null, "it", now)).isTrue();

        UUID incidentId = insertIncident("digest-" + UUID.randomUUID());
        RcaRun oldRun = run(incidentId);
        runs.insertRouted(oldRun, new RcaRunRouting(
                RcaEngine.NATIVE, v1, "alertname=higherror|service=checkout", 12,
                CanaryDecision.BUCKETED_NATIVE.name()));

        // 指针移到 v2（发布新版本 = 回滚语义：activate 到旧 digest 同一路径）
        assertThat(bundles.activate(v2, v1, "it", now)).isTrue();

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
