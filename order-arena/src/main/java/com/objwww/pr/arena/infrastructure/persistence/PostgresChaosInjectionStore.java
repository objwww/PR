package com.objwww.pr.arena.infrastructure.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

/**
 * chaos 注入/恢复的 arena 侧 SQL 面（M2-18/19）。
 *
 * <p>F2 回跳注入是<b>刻意绕过状态机</b>的直写 SQL（ENABLED→CREATED 的非法迁移正是
 * 注入本体；BookingStateMachine 不允许它，所以注入不走状态机）。恢复走 C-4 事实驱动：
 * 仅当事实未变（enabled_at 非空 + AUTH 支付事实 SUCCEEDED）才回写 ENABLED——
 * 注入可逆性建立在"只改状态位、不动任何事实"上。
 *
 * <p>F1 恢复的重复分析（同 intent 多个未废订单）在此只读，补偿动作由服务层经
 * OrderCreationSteps/RefundChainService 走正常业务路径（canonical=最早保留）。
 */
public class PostgresChaosInjectionStore {

    /** F1 恢复候选行（同 intent 重复单分析） */
    public record DuplicateRow(UUID orderId, String intentId, String bookingStatus,
                               String payStatus, boolean canonical) {
    }

    private final JdbcClient jdbc;

    public PostgresChaosInjectionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- F2 注入 ----------

    /** F2 靶面：target 前缀圈定的 ENABLED 订单（排除已注入） */
    public List<UUID> findF2Targets(UUID sessionId, String target, int limit) {
        return jdbc.sql("""
                SELECT t.id FROM arena.oa_trade_order t
                WHERE t.booking_status = 'ENABLED'
                  AND (:target IS NULL OR t.correlation_id LIKE :target || '%')
                  AND NOT EXISTS (
                      SELECT 1 FROM arena.oa_injection_audit a
                      WHERE a.session_id = :session AND a.order_id = t.id
                        AND a.action = 'INJECTED')
                ORDER BY t.created_at
                LIMIT :limit
                """)
                .param("session", sessionId)
                .param("target", target)
                .param("limit", limit)
                .query((rs, i) -> UUID.fromString(rs.getString("id")))
                .list();
    }

    /**
     * F2 注入本体：状态位直写回跳（非法迁移 = 注入语义）+ 注入审计（幂等锚：
     * uq_injection_once 保证重复扫描不重复入账）。@return 是否真的发生了回跳
     */
    public boolean injectF2Backjump(UUID sessionId, UUID orderId) {
        boolean flipped = jdbc.sql("""
                UPDATE arena.oa_trade_order
                SET booking_status = 'CREATED', updated_at = now()
                WHERE id = :id AND booking_status = 'ENABLED'
                """).param("id", orderId).update() == 1;
        if (flipped) {
            jdbc.sql("""
                    INSERT INTO arena.oa_injection_audit(id, session_id, fault_type, order_id,
                        action, detail)
                    VALUES (:id, :session, 'F2', :order, 'INJECTED',
                            jsonb_build_object('path', 'direct-sql-backjump'))
                    ON CONFLICT DO NOTHING
                    """)
                    .param("id", UUID.randomUUID()).param("session", sessionId)
                    .param("order", orderId).update();
        }
        return flipped;
    }

    // ---------- F2 恢复（C-4 事实驱动） ----------

    /** 本会话尚未配对恢复的注入单（与 chaos-admin 闭会话判定同款谓词；缺一不可闭） */
    public List<UUID> findInjectedWithoutRecovered(UUID sessionId) {
        return jdbc.sql("""
                SELECT a.order_id FROM arena.oa_injection_audit a
                WHERE a.session_id = :session AND a.action = 'INJECTED'
                  AND NOT EXISTS (
                      SELECT 1 FROM arena.oa_injection_audit r
                      WHERE r.session_id = a.session_id AND r.order_id = a.order_id
                        AND r.action = 'RECOVERED')
                """).param("session", sessionId)
                .query((rs, i) -> UUID.fromString(rs.getString("order_id")))
                .list();
    }

    /** 恢复回写（CREATED→ENABLED，事实守卫 CAS：回跳态 + enabled_at + AUTH SUCCEEDED） */
    public boolean restoreF2(UUID orderId) {
        return jdbc.sql("""
                UPDATE arena.oa_trade_order
                SET booking_status = 'ENABLED', updated_at = now()
                WHERE id = :id AND booking_status = 'CREATED' AND enabled_at IS NOT NULL
                  AND EXISTS (
                      SELECT 1 FROM arena.oa_payment_record p
                      WHERE p.order_id = :id AND p.kind = 'AUTH' AND p.result = 'SUCCEEDED')
                """).param("id", orderId).update() == 1;
    }

    /** 逐单恢复审计（闭会话配对面；幂等锚 uq_injection_once(session,order,RECOVERED)） */
    public void auditOrderRecovered(UUID sessionId, String faultType, UUID orderId,
                                    String detail) {
        jdbc.sql("""
                INSERT INTO arena.oa_injection_audit(id, session_id, fault_type, order_id,
                    action, detail)
                VALUES (:id, :session, :fault, :order, 'RECOVERED',
                        jsonb_build_object('detail', :detail))
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.randomUUID()).param("session", sessionId)
                .param("fault", faultType).param("order", orderId)
                .param("detail", detail).update();
    }

    // ---------- F1 恢复（分析面） ----------

    /** 同 intent 多单的重复组（仅 chaos- 流量；canonical = created_at 最早，平局取 id 序） */
    public List<DuplicateRow> findF1DuplicateRows(String target) {
        return jdbc.sql("""
                WITH ranked AS (
                    SELECT t.id, t.intent_id, t.booking_status, t.pay_status, t.correlation_id,
                           row_number() OVER (PARTITION BY t.intent_id
                                              ORDER BY t.created_at, t.id) AS rn
                    FROM arena.oa_trade_order t
                    WHERE t.booking_status <> 'DISCARDED'
                      AND t.correlation_id LIKE 'chaos-%'
                      AND (:target IS NULL OR t.correlation_id LIKE :target || '%')
                )
                SELECT r.id, r.intent_id, r.booking_status, r.pay_status, r.rn
                FROM ranked r
                JOIN (SELECT intent_id FROM ranked GROUP BY intent_id HAVING count(*) > 1) d
                  ON d.intent_id = r.intent_id
                ORDER BY r.intent_id, r.rn
                """).param("target", target)
                .query((rs, i) -> new DuplicateRow(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("intent_id"),
                        rs.getString("booking_status"),
                        rs.getString("pay_status"),
                        rs.getInt("rn") == 1))
                .list();
    }

    // ---------- 审计（会话级收口，幂等） ----------

    public void auditSessionRecovered(UUID sessionId, String faultType, String detail) {
        jdbc.sql("""
                INSERT INTO arena.oa_injection_audit(id, session_id, fault_type, order_id,
                    action, detail)
                VALUES (:id, :session, :fault, null, 'RECOVERED',
                        jsonb_build_object('detail', :detail))
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.randomUUID()).param("session", sessionId)
                .param("fault", faultType).param("detail", detail).update();
    }

    /** 会话级 RECOVERED 是否已落（恢复收口判重） */
    public boolean hasSessionRecovered(UUID sessionId) {
        Integer n = jdbc.sql("""
                SELECT count(*) FROM arena.oa_injection_audit
                WHERE session_id = :session AND action = 'RECOVERED' AND order_id IS NULL
                """).param("session", sessionId)
                .query(Integer.class).single();
        return n != null && n > 0;
    }

    /** scenario_id → 会话主键（业务身份全域唯一；恢复审计按主键外键落行） */
    public UUID sessionIdOf(String scenarioId) {
        return jdbc.sql("SELECT id FROM arena.oa_chaos_session WHERE scenario_id = :s")
                .param("s", scenarioId)
                .query((rs, i) -> UUID.fromString(rs.getString("id")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("chaos 会话不存在: " + scenarioId));
    }
}
