package com.objwww.pr.arena.infrastructure.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DomainProbe 的 SQL 面（M2-21）：三类事实违规查询 + episode 台账同步。
 * 所有判定以<b>数据库事实</b>为准（不读应用内存态），探测 = 纯读 + 幂等写。
 * 违规形态指纹（violation_digest）由调用方 Java 侧计算（不依赖 pgcrypto）。
 */
public class PostgresProbeStore {

    /** 一条违规事实：主体 + 形态标记（digest 由调用方合成） */
    public record Violation(String entityId, String variant) {
    }

    /** 打开的 episode 行 */
    public record OpenFinding(UUID id, String findingType, String entityId,
                              String violationDigest) {
    }

    private final JdbcClient jdbc;

    public PostgresProbeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 事实违规查询（truth queries） ----------

    /** 卡单（F3 症状）：CREATED 停留超阈值 */
    public List<Violation> stuckOrders(int olderThanSeconds) {
        return jdbc.sql("""
                SELECT id::text AS entity
                FROM arena.oa_trade_order
                WHERE booking_status = 'CREATED'
                  AND created_at < now() - make_interval(secs => :sec)
                """).param("sec", olderThanSeconds)
                .query((rs, i) -> new Violation(rs.getString("entity"), "stuck"))
                .list();
    }

    /** 重复单（F1 症状）：同 intent 多个未废订单（canonical=最小 id 之外都计） */
    public List<Violation> duplicateOrders() {
        return jdbc.sql("""
                WITH dup AS (
                    SELECT intent_id FROM arena.oa_trade_order
                    WHERE booking_status <> 'DISCARDED'
                    GROUP BY intent_id HAVING count(*) > 1
                )
                SELECT t.id::text AS entity
                FROM arena.oa_trade_order t JOIN dup ON dup.intent_id = t.intent_id
                WHERE t.booking_status <> 'DISCARDED'
                  AND t.id::text <> (SELECT min(t2.id::text) FROM arena.oa_trade_order t2
                               WHERE t2.intent_id = t.intent_id
                                 AND t2.booking_status <> 'DISCARDED')
                """)
                .query((rs, i) -> new Violation(rs.getString("entity"), "dup"))
                .list();
    }

    /** 状态违规（F2 症状）：回跳签名 + PAID 无支付事实 */
    public List<Violation> stateViolations() {
        return jdbc.sql("""
                SELECT id::text AS entity, 'backjump' AS variant
                FROM arena.oa_trade_order
                WHERE booking_status = 'CREATED' AND enabled_at IS NOT NULL
                UNION ALL
                SELECT t.id::text AS entity, 'paid-without-fact' AS variant
                FROM arena.oa_trade_order t
                WHERE t.pay_status = 'PAID'
                  AND NOT EXISTS (SELECT 1 FROM arena.oa_payment_record p
                                  WHERE p.order_id = t.id AND p.result = 'SUCCEEDED')
                """)
                .query((rs, i) -> new Violation(rs.getString("entity"), rs.getString("variant")))
                .list();
    }

    // ---------- episode 台账（C-7：开/续/关/复发=新号） ----------

    public Map<String, OpenFinding> openFindings(String findingType) {
        Map<String, OpenFinding> byEntity = new HashMap<>();
        jdbc.sql("""
                SELECT id, entity_id, violation_digest FROM arena.oa_probe_finding
                WHERE finding_type = :type AND resolved_at IS NULL
                """).param("type", findingType)
                .query((rs, i) -> new OpenFinding(
                        UUID.fromString(rs.getString("id")),
                        findingType,
                        rs.getString("entity_id"),
                        rs.getString("violation_digest")))
                .list()
                .forEach(f -> byEntity.put(f.entityId(), f));
        return byEntity;
    }

    /** 复发新 episode（episode_no = 同型同实体已有序号 max+1） */
    public void openEpisode(String findingType, String entityId, String digest) {
        jdbc.sql("""
                INSERT INTO arena.oa_probe_finding(id, finding_type, entity_id,
                    violation_digest, episode_no)
                VALUES (:id, :type, :entity, :dg,
                        (SELECT COALESCE(max(episode_no), 0) + 1
                         FROM arena.oa_probe_finding
                         WHERE finding_type = :type AND entity_id = :entity))
                """)
                .param("id", UUID.randomUUID()).param("type", findingType)
                .param("entity", entityId).param("dg", digest).update();
    }

    public void touchEpisode(String findingType, String entityId, String digest) {
        jdbc.sql("""
                UPDATE arena.oa_probe_finding
                SET last_seen_at = now(), violation_digest = :dg
                WHERE finding_type = :type AND entity_id = :entity AND resolved_at IS NULL
                """).param("dg", digest).param("type", findingType)
                .param("entity", entityId).update();
    }

    public void closeEpisodes(String findingType, List<String> entitiesToClose) {
        if (entitiesToClose.isEmpty()) {
            return;
        }
        jdbc.sql("""
                UPDATE arena.oa_probe_finding
                SET resolved_at = now()
                WHERE finding_type = :type AND resolved_at IS NULL
                  AND entity_id IN (:entities)
                """).param("type", findingType).param("entities", entitiesToClose)
                .update();
    }
}
