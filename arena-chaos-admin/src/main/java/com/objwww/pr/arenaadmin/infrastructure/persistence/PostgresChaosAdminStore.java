package com.objwww.pr.arenaadmin.infrastructure.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * arena-chaos-admin 的 SQL 面（M2-17/24）：chaos 域唯一写者（C-3）。
 * 激活 = 单事务四写（GT + session ACTIVE + event + scenario_map），任一失败全回滚。
 * 会话推进全走 CAS（generation 单调），恢复收口依 oa_injection_audit 只读判定。
 */
public class PostgresChaosAdminStore {

    /** 会话状态视图 */
    public record SessionRow(UUID id, String scenarioId, String faultType, String target,
                             int ttlSeconds, String operator, String state, long generation,
                             java.time.Instant createdAt, java.time.Instant expiresAt) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public PostgresChaosAdminStore(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    // ---------- 激活（M2-17 激活事务，四约束由 V3 的 DB 约束兜底） ----------

    public record Activation(UUID gtId, UUID sessionId, String scenarioId, String faultType,
                             String alertFingerprint) {
    }

    public Activation activate(String scenarioId, String faultType, String target, int ttlSeconds,
                               String operator, String configDigest,
                               GtFields gt, Map<String, String> alertLabels,
                               String ruleDigest) {
        UUID gtId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String fingerprint = com.objwww.pr.arenaadmin.fingerprint.AlertmanagerFingerprint
                .of(alertLabels);
        tx.executeWithoutResult(status -> {
            jdbc.sql("""
                    INSERT INTO arena.ground_truth_scenario(id, schema_version, dataset_version,
                        scenario_id, activation_generation, config_digest, payload_digest,
                        applicable_scope, valid_from, review_status)
                    VALUES (:id, :schemaVersion, :datasetVersion, :scenarioId, 0,
                            :configDigest, :payloadDigest, :scope, now(), 'CONFIRMED')
                    """)
                    .param("id", gtId).param("schemaVersion", gt.schemaVersion())
                    .param("datasetVersion", gt.datasetVersion())
                    .param("scenarioId", scenarioId)
                    .param("configDigest", configDigest)
                    .param("payloadDigest", gt.payloadDigest())
                    .param("scope", gt.applicableScope()).update();
            jdbc.sql("""
                    INSERT INTO arena.oa_chaos_session(id, scenario_id, fault_type, target,
                        ttl_seconds, operator, config_digest, state, generation,
                        expires_at)
                    VALUES (:id, :scenarioId, :faultType, :target, :ttl, :operator,
                            :configDigest, 'ACTIVE', 0, now() + make_interval(secs => :ttl))
                    """)
                    .param("id", sessionId).param("scenarioId", scenarioId)
                    .param("faultType", faultType).param("target", target)
                    .param("ttl", ttlSeconds).param("operator", operator)
                    .param("configDigest", configDigest).param("ttl", ttlSeconds).update();
            jdbc.sql("""
                    INSERT INTO arena.oa_chaos_event(id, session_id, event_type, detail)
                    VALUES (:id, :session, 'ACTIVATED',
                            jsonb_build_object('operator', :operator, 'ttl_seconds', :ttl))
                    """)
                    .param("id", UUID.randomUUID()).param("session", sessionId)
                    .param("operator", operator).param("ttl", ttlSeconds).update();
            jdbc.sql("""
                    INSERT INTO arena.oa_scenario_map(id, scenario_id, mapping_version,
                        alert_fingerprint, alert_labels, rule_digest)
                    VALUES (:id, :scenarioId, 1, :fp,
                            cast(:labels as jsonb), :ruleDigest)
                    """)
                    .param("id", UUID.randomUUID()).param("scenarioId", scenarioId)
                    .param("fp", fingerprint)
                    .param("labels", jsonOf(alertLabels)).param("ruleDigest", ruleDigest)
                    .update();
        });
        return new Activation(gtId, sessionId, scenarioId, faultType, fingerprint);
    }

    /** GT 冻结字段（C-5 激活必带） */
    public record GtFields(Integer schemaVersion, String datasetVersion, String payloadDigest,
                           String applicableScope) {
    }

    private String jsonOf(Map<String, String> labels) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : new TreeMap<>(labels).entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey().replace("\"", "\\\"")).append("\":\"")
                    .append(e.getValue().replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    // ---------- 关闭（CAS：generation 单调，expectedGeneration 不符即拒） ----------

    /** @return false = CAS 未中（状态/代数不符/会话不存在） */
    public boolean casRecovering(String scenarioId, long expectedGeneration) {
        int n = jdbc.sql("""
                UPDATE arena.oa_chaos_session
                SET state = 'RECOVERING', generation = generation + 1, updated_at = now()
                WHERE scenario_id = :s AND state = 'ACTIVE' AND generation = :gen
                """).param("s", scenarioId).param("gen", expectedGeneration).update();
        if (n == 1) {
            jdbc.sql("""
                    INSERT INTO arena.oa_chaos_event(id, session_id, event_type, detail)
                    SELECT :id, id, 'DEACTIVATED',
                           jsonb_build_object('expected_generation', :gen)
                    FROM arena.oa_chaos_session WHERE scenario_id = :s
                    """).param("id", UUID.randomUUID())
                    .param("gen", expectedGeneration).param("s", scenarioId).update();
            return true;
        }
        return false;
    }

    // ---------- TTL reaper（M2-17 崩溃兜底） ----------

    /** 过期 ACTIVE → RECOVERING（TTL_EXPIRED 事件）；@return 处理数 */
    public int reapExpired() {
        Integer n = tx.execute(status -> jdbc.sql("""
                WITH expired AS (
                    UPDATE arena.oa_chaos_session
                    SET state = 'RECOVERING', updated_at = now()
                    WHERE state = 'ACTIVE' AND expires_at <= now()
                    RETURNING id
                )
                SELECT count(*) FROM expired
                """).query(Integer.class).single());
        if (n != null && n > 0) {
            jdbc.sql("""
                    INSERT INTO arena.oa_chaos_event(id, session_id, event_type, detail)
                    SELECT :id, s.id, 'TTL_EXPIRED', '{}'
                    FROM arena.oa_chaos_session s
                    WHERE s.state = 'RECOVERING'
                      AND s.expires_at <= now()
                      AND NOT EXISTS (SELECT 1 FROM arena.oa_chaos_event e
                                      WHERE e.session_id = s.id
                                        AND e.event_type = 'TTL_EXPIRED')
                    """).param("id", UUID.randomUUID()).update();
        }
        return n == null ? 0 : n;
    }

    /** 启动清扫：孤儿 PREPARED 超龄 → CLOSED（STARTUP_REAPED） */
    public int reapStartupOrphans() {
        Integer n = tx.execute(status -> jdbc.sql("""
                WITH orphans AS (
                    UPDATE arena.oa_chaos_session
                    SET state = 'CLOSED', updated_at = now()
                    WHERE state = 'PREPARED' AND created_at < now() - interval '10 minutes'
                    RETURNING id
                )
                SELECT count(*) FROM orphans
                """).query(Integer.class).single());
        if (n != null && n > 0) {
            jdbc.sql("""
                    INSERT INTO arena.oa_chaos_event(id, session_id, event_type, detail)
                    SELECT :id, id, 'STARTUP_REAPED', '{}' FROM arena.oa_chaos_session
                    WHERE state = 'CLOSED'
                      AND id IN (SELECT session_id FROM arena.oa_chaos_event
                                 WHERE event_type = 'STARTUP_REAPED')
                    """).param("id", UUID.randomUUID()).update();
        }
        return n == null ? 0 : n;
    }

    // ---------- 恢复收口（依注入审计只读判定，M2-18/19/20 的会话级终点） ----------

    /** RECOVERING 且审计显示恢复完成（有会话级 RECOVERED 且无未收口的 INJECTED 单）→ CLOSED */
    public int closeRecovered() {
        Integer n = tx.execute(status -> jdbc.sql("""
                WITH done AS (
                    UPDATE arena.oa_chaos_session s
                    SET state = 'CLOSED', updated_at = now()
                    WHERE s.state = 'RECOVERING'
                      AND EXISTS (SELECT 1 FROM arena.oa_injection_audit a
                                  WHERE a.session_id = s.id AND a.action = 'RECOVERED'
                                    AND a.order_id IS NULL)
                      AND NOT EXISTS (SELECT 1 FROM arena.oa_injection_audit a
                                      WHERE a.session_id = s.id AND a.action = 'INJECTED'
                                        AND NOT EXISTS (
                                            SELECT 1 FROM arena.oa_injection_audit r
                                            WHERE r.session_id = a.session_id
                                              AND r.order_id = a.order_id
                                              AND r.action = 'RECOVERED'))
                    RETURNING id
                )
                SELECT count(*) FROM done
                """).query(Integer.class).single());
        if (n != null && n > 0) {
            jdbc.sql("""
                    INSERT INTO arena.oa_chaos_event(id, session_id, event_type, detail)
                    SELECT :id, s.id, 'RECOVERY_COMPLETED', '{}' FROM arena.oa_chaos_session s
                    WHERE s.state = 'CLOSED' AND s.scenario_id IN (
                        SELECT DISTINCT s2.scenario_id FROM arena.oa_chaos_session s2
                        JOIN arena.oa_injection_audit a ON a.session_id = s2.id
                        WHERE s2.state = 'CLOSED' AND a.action = 'RECOVERED')
                      AND NOT EXISTS (SELECT 1 FROM arena.oa_chaos_event e
                                      WHERE e.session_id = s.id
                                        AND e.event_type = 'RECOVERY_COMPLETED')
                    """).param("id", UUID.randomUUID()).update();
        }
        return n == null ? 0 : n;
    }

    // ---------- 状态查询 ----------

    public Optional<SessionRow> findSession(String scenarioId) {
        return jdbc.sql("""
                SELECT id, scenario_id, fault_type, target, ttl_seconds, operator, state,
                       generation, created_at, expires_at
                FROM arena.oa_chaos_session WHERE scenario_id = :s
                """).param("s", scenarioId)
                .query((rs, i) -> new SessionRow(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("scenario_id"),
                        rs.getString("fault_type"),
                        rs.getString("target"),
                        rs.getInt("ttl_seconds"),
                        rs.getString("operator"),
                        rs.getString("state"),
                        rs.getLong("generation"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    public Map<String, Integer> auditSummary(UUID sessionId) {
        Map<String, Integer> out = new HashMap<>();
        jdbc.sql("""
                SELECT action, count(*) AS n FROM arena.oa_injection_audit
                WHERE session_id = :s GROUP BY action
                """).param("s", sessionId)
                .query((rs, i) -> Map.entry(rs.getString("action"), rs.getInt("n")))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    // ---------- 场景图回填（M2-24：事件绑定 = 新版本行；旧代迟到事件拒绝） ----------

    public record BackfillResult(long mappingVersion, String alertFingerprint) {
    }

    /** @return null = 会话不存在或事件代数过期（不串场） */
    public BackfillResult backfillIncident(String scenarioId, String incidentId,
                                           long incidentGeneration, String runId,
                                           String reportId) {
        return tx.execute(status -> {
            var session = findSession(scenarioId).orElse(null);
            if (session == null || incidentGeneration < session.generation()) {
                return null;
            }
            var latest = jdbc.sql("""
                    SELECT mapping_version, alert_fingerprint, alert_labels, rule_digest
                    FROM arena.oa_scenario_map WHERE scenario_id = :s
                    ORDER BY mapping_version DESC LIMIT 1
                    """).param("s", scenarioId)
                    .query((rs, i) -> new long[] { rs.getLong(1) })
                    .optional();
            if (latest.isEmpty()) {
                return null;
            }
            long nextVersion = latest.get()[0] + 1;
            String fingerprint = jdbc.sql("""
                    SELECT alert_fingerprint FROM arena.oa_scenario_map
                    WHERE scenario_id = :s AND mapping_version = :v
                    """).param("s", scenarioId).param("v", nextVersion - 1)
                    .query(String.class).single();
            jdbc.sql("""
                    INSERT INTO arena.oa_scenario_map(id, scenario_id, mapping_version,
                        alert_fingerprint, alert_labels, rule_digest, incident_id,
                        incident_generation, run_id, report_id)
                    SELECT :id, scenario_id, :v, alert_fingerprint, alert_labels, rule_digest,
                           :incident, :gen, :run, :report
                    FROM arena.oa_scenario_map
                    WHERE scenario_id = :s AND mapping_version = :prev
                    """)
                    .param("id", UUID.randomUUID()).param("v", nextVersion)
                    .param("incident", incidentId).param("gen", incidentGeneration)
                    .param("run", runId).param("report", reportId)
                    .param("s", scenarioId).param("prev", nextVersion - 1).update();
            return new BackfillResult(nextVersion, fingerprint);
        });
    }
}
