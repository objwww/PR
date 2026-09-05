package com.objwww.pr.arenaadmin.it;

import com.objwww.pr.arenaadmin.application.ChaosActivationService;
import com.objwww.pr.arenaadmin.infrastructure.persistence.PostgresChaosAdminStore;
import com.objwww.pr.arenaadmin.infrastructure.persistence.PostgresChaosAdminStore.Activation;
import com.objwww.pr.arenaadmin.infrastructure.persistence.PostgresChaosAdminStore.BackfillResult;
import com.objwww.pr.arenaadmin.infrastructure.persistence.PostgresChaosAdminStore.GtFields;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2-17/24 激活生命周期 IT（真库真约束）：
 * 激活单事务四写（GT+session ACTIVE+event+scenario_map）、四约束兜底、
 * CAS off、依注入审计的 RECOVERING→CLOSED 收口、场景图版本行回填（旧代拒绝）。
 */
class ChaosActivationLifecycleIT extends ChaosAdminPostgresITBase {

    private ChaosActivationService service() {
        return new ChaosActivationService(
                new PostgresChaosAdminStore(chaosAdminJdbc, chaosAdminTx), 30, 7200);
    }

    private static final Map<String, String> ALERT_LABELS = Map.of(
            "alertname", "ArenaOrderStuck",
            "fault_type", "F3",
            "service", "order-arena",
            "job", "order-arena",
            "instance", "order-arena:8080",
            "severity", "page");

    private static final GtFields GT = new GtFields(1, "it-ds", "b".repeat(64), "arena");

    @Test
    void 激活单事务四写_指纹落图() {
        Activation activation = service().activate("F3", "f3-life-001", "chaos-f3life",
                600, "it-op", "a".repeat(64), GT, ALERT_LABELS, "c".repeat(64));

        assertThat(sessionState("f3-life-001")).isEqualTo("ACTIVE");
        assertThat(sessionGeneration("f3-life-001")).isZero();
        assertThat(count("ground_truth_scenario")).isEqualTo(1);
        assertThat(count("oa_chaos_event")).isEqualTo(1);
        // C-6 指纹：期望值为冻结常量（2026-09-05 按 195 真栈 AM API ground truth
        // 校准算法后固化；向量细节见 AlertmanagerFingerprintTest）
        String fp = adminJdbc.sql("""
                SELECT alert_fingerprint FROM arena.oa_scenario_map
                WHERE scenario_id = 'f3-life-001' AND mapping_version = 1
                """).query(String.class).single();
        assertThat(fp).isEqualTo("f95e79c26f0e7b4c");
        assertThat(activation.alertFingerprint()).isEqualTo("f95e79c26f0e7b4c");
    }

    @Test
    void 场景重复_唯一约束兜底409语义() {
        service().activate("F3", "f3-life-dup", "chaos-dup", 600, "it-op",
                "a".repeat(64), GT, ALERT_LABELS, "c".repeat(64));
        assertThatThrownBy(() -> service().activate("F3", "f3-life-dup", "chaos-dup",
                600, "it-op", "a".repeat(64), GT, ALERT_LABELS, "c".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 同型同靶并发激活_部分唯一索引兜底() {
        service().activate("F1", "f1-life-a", "chaos-same-target", 600, "it-op",
                "a".repeat(64), GT, ALERT_LABELS, "c".repeat(64));
        assertThatThrownBy(() -> service().activate("F1", "f1-life-b", "chaos-same-target",
                600, "it-op", "a".repeat(64), GT, ALERT_LABELS, "c".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void off_CAS_代数不符拒绝_相符进入RECOVERING() {
        service().activate("F3", "f3-life-off", "chaos-off", 600, "it-op",
                "a".repeat(64), GT, ALERT_LABELS, "c".repeat(64));

        assertThat(service().deactivate("f3-life-off", 9L)).isFalse(); // 代数不符
        assertThat(sessionState("f3-life-off")).isEqualTo("ACTIVE");

        assertThat(service().deactivate("f3-life-off", 0L)).isTrue();
        assertThat(sessionState("f3-life-off")).isEqualTo("RECOVERING");
        assertThat(sessionGeneration("f3-life-off")).isEqualTo(1);
        // off 不可重复（已非 ACTIVE）
        assertThat(service().deactivate("f3-life-off", 1L)).isFalse();
    }

    @Test
    void 恢复收口_依注入审计判定_无未收口INJECTED才CLOSED() {
        UUID sessionId = service().activate("F2", "f2-life-close", "chaos-close",
                600, "it-op", "a".repeat(64), GT, ALERT_LABELS, "c".repeat(64))
                .sessionId();
        service().deactivate("f2-life-close", 0L);

        // 仅有会话级 RECOVERED、无未收口 INJECTED → CLOSED
        seedInjectionAudit(sessionId, "F2", "RECOVERED", null);
        service().reaperTick();
        assertThat(sessionState("f2-life-close")).isEqualTo("CLOSED");
        Long completedEvents = adminJdbc.sql("""
                SELECT count(*) FROM arena.oa_chaos_event e
                JOIN arena.oa_chaos_session s ON s.id = e.session_id
                WHERE s.scenario_id = 'f2-life-close' AND e.event_type = 'RECOVERY_COMPLETED'
                """).query(Long.class).single();
        assertThat(completedEvents).isEqualTo(1L);
    }

    @Test
    void 恢复收口_存在未收口INJECTED_保持RECOVERING() {
        UUID sessionId = service().activate("F2", "f2-life-hang", "chaos-hang",
                600, "it-op", "a".repeat(64), GT, ALERT_LABELS, "c".repeat(64))
                .sessionId();
        service().deactivate("f2-life-hang", 0L);

        seedInjectionAudit(sessionId, "F2", "INJECTED", UUID.randomUUID()); // 无配对 RECOVERED
        seedInjectionAudit(sessionId, "F2", "RECOVERED", null);
        service().reaperTick();
        assertThat(sessionState("f2-life-hang")).isEqualTo("RECOVERING");
    }

    @Test
    void TTL过期_reaper推入RECOVERING() {
        UUID sessionId = service().activate("F3", "f3-life-ttl", "chaos-ttl",
                30, "it-op", "a".repeat(64), GT, ALERT_LABELS, "c".repeat(64))
                .sessionId();
        adminJdbc.sql("""
                UPDATE arena.oa_chaos_session
                SET created_at = now() - interval '2 hours',
                    expires_at = now() - interval '1 second'
                WHERE id = :id
                """).param("id", sessionId).update();

        service().reaperTick();
        assertThat(sessionState("f3-life-ttl")).isEqualTo("RECOVERING");
        Long ttlEvents = adminJdbc.sql("""
                SELECT count(*) FROM arena.oa_chaos_event e
                JOIN arena.oa_chaos_session s ON s.id = e.session_id
                WHERE s.scenario_id = 'f3-life-ttl' AND e.event_type = 'TTL_EXPIRED'
                """).query(Long.class).single();
        assertThat(ttlEvents).isEqualTo(1L);
    }

    @Test
    void 场景图回填_新版本行_旧代事件拒绝() {
        service().activate("F3", "f3-life-map", "chaos-map", 600, "it-op",
                "a".repeat(64), GT, ALERT_LABELS, "c".repeat(64));

        // 未知场景 → 拒绝
        assertThat(service().backfillIncident("no-such", "INC-1", 0L, "run", "rep"))
                .isNull();

        // 正常回填 → v2 行携带事件绑定
        BackfillResult ok = service().backfillIncident("f3-life-map", "INC-42",
                0L, "run-7", "rep-7");
        assertThat(ok).isNotNull();
        assertThat(ok.mappingVersion()).isEqualTo(2L);
        assertThat(ok.alertFingerprint()).isEqualTo("f95e79c26f0e7b4c");
        String incident = adminJdbc.sql("""
                SELECT incident_id FROM arena.oa_scenario_map
                WHERE scenario_id = 'f3-life-map' AND mapping_version = 2
                """).query(String.class).single();
        assertThat(incident).isEqualTo("INC-42");

        // off 后 generation=1：迟到事件携带旧代 → 不串场
        service().deactivate("f3-life-map", 0L);
        assertThat(service().backfillIncident("f3-life-map", "INC-43", 0L, "run", "rep"))
                .isNull();
        BackfillResult current = service().backfillIncident("f3-life-map", "INC-44",
                1L, "run", "rep");
        assertThat(current).isNotNull();
        assertThat(current.mappingVersion()).isEqualTo(3L);
    }
}
