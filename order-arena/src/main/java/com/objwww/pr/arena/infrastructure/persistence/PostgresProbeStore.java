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
    private final int lookbackSeconds;

    public PostgresProbeStore(JdbcClient jdbc) {
        this(jdbc, 1800);
    }

    /**
     * @param lookbackSeconds 违规事实查询的回看窗（秒）——靶场表随流量单调增长（百万行级），
     *                        无界扫描使探测一轮退化到分钟级（T8 真机事故）；告警语义只关心
     *                        当前症状，历史损伤由 episode 台账承载，故按近期窗口圈定
     */
    public PostgresProbeStore(JdbcClient jdbc, int lookbackSeconds) {
        this.jdbc = jdbc;
        this.lookbackSeconds = lookbackSeconds;
    }

    // ---------- 事实违规查询（truth queries） ----------

    /** 卡单（F3 症状）：CREATED 停留超阈值（近期窗口内） */
    public List<Violation> stuckOrders(int olderThanSeconds) {
        return jdbc.sql("""
                SELECT id::text AS entity
                FROM arena.oa_trade_order
                WHERE booking_status = 'CREATED'
                  AND created_at < now() - make_interval(secs => :sec)
                  AND created_at >= now() - make_interval(secs => :lb)
                """).param("sec", olderThanSeconds)
                .param("lb", lookbackSeconds)
                .query((rs, i) -> new Violation(rs.getString("entity"), "stuck"))
                .list();
    }

    /** 重复单（F1 症状）：同 intent 多个未废订单（canonical=最小 id 之外都计；近期窗口内） */
    public List<Violation> duplicateOrders() {
        return jdbc.sql("""
                WITH dup AS (
                    SELECT intent_id FROM arena.oa_trade_order
                    WHERE booking_status <> 'DISCARDED'
                      AND created_at >= now() - make_interval(secs => :lb)
                    GROUP BY intent_id HAVING count(*) > 1
                )
                SELECT t.id::text AS entity
                FROM arena.oa_trade_order t JOIN dup ON dup.intent_id = t.intent_id
                WHERE t.booking_status <> 'DISCARDED'
                  AND t.created_at >= now() - make_interval(secs => :lb)
                  AND t.id::text <> (SELECT min(t2.id::text) FROM arena.oa_trade_order t2
                               WHERE t2.intent_id = t.intent_id
                                 AND t2.booking_status <> 'DISCARDED')
                """).param("lb", lookbackSeconds)
                .query((rs, i) -> new Violation(rs.getString("entity"), "dup"))
                .list();
    }

    /** 状态违规（F2 症状）：回跳签名 + PAID 无支付事实（近期窗口内） */
    public List<Violation> stateViolations() {
        return jdbc.sql("""
                SELECT id::text AS entity, 'backjump' AS variant
                FROM arena.oa_trade_order
                WHERE booking_status = 'CREATED' AND enabled_at IS NOT NULL
                  AND created_at >= now() - make_interval(secs => :lb)
                UNION ALL
                SELECT t.id::text AS entity, 'paid-without-fact' AS variant
                FROM arena.oa_trade_order t
                WHERE t.pay_status = 'PAID'
                  AND t.created_at >= now() - make_interval(secs => :lb)
                  AND NOT EXISTS (SELECT 1 FROM arena.oa_payment_record p
                                  WHERE p.order_id = t.id AND p.result = 'SUCCEEDED')
                """).param("lb", lookbackSeconds)
                .query((rs, i) -> new Violation(rs.getString("entity"), rs.getString("variant")))
                .list();
    }

    // ---------- M-a 业务对账事实查询（S16~S20，v2 设计 §3.1） ----------

    /**
     * 掉单（F9 症状）：CAPTURE 支付成功、订单仍 ENABLED/NOT_PAY——支付事实已落、
     * 订单状态未推进（回调被吞的账面痕迹）。废单（DISCARDED）不计——其 CAPTURE
     * 成功走退款链属正常。
     */
    public List<Violation> paymentOrderMismatches() {
        return jdbc.sql("""
                SELECT DISTINCT t.id::text AS entity, 'capture-no-paidsync' AS variant
                FROM arena.oa_payment_record p
                  JOIN arena.oa_trade_order t ON t.id = p.order_id
                WHERE p.kind = 'CAPTURE' AND p.result = 'SUCCEEDED'
                  AND t.booking_status = 'ENABLED' AND t.pay_status = 'NOT_PAY'
                  AND t.created_at >= now() - make_interval(secs => :lb)
                """).param("lb", lookbackSeconds)
                .query((rs, i) -> new Violation(rs.getString("entity"), rs.getString("variant")))
                .list();
    }

    /** 支付悬挂（F10 症状）：AUTH 发起后沉默停 INITIATED（无结果落定，区别于 F3 UNKNOWN；近期窗口内） */
    public List<Violation> pendingPaymentOrders(int olderThanSeconds) {
        return jdbc.sql("""
                SELECT p.order_id::text AS entity, 'auth-initiated-stuck' AS variant
                FROM arena.oa_payment_record p
                  JOIN arena.oa_trade_order t ON t.id = p.order_id
                WHERE p.kind = 'AUTH' AND p.result = 'INITIATED'
                  AND p.initiated_at < now() - make_interval(secs => :sec)
                  AND t.booking_status = 'CREATED'
                  AND t.created_at >= now() - make_interval(secs => :lb)
                """).param("sec", olderThanSeconds)
                .param("lb", lookbackSeconds)
                .query((rs, i) -> new Violation(rs.getString("entity"), rs.getString("variant")))
                .list();
    }

    /** 重复扣款（F11 症状）：同订单多笔 CAPTURE 成功（近期窗口内） */
    public List<Violation> duplicatePayments() {
        return jdbc.sql("""
                SELECT order_id::text AS entity, 'multi-capture' AS variant
                FROM arena.oa_payment_record
                WHERE kind = 'CAPTURE' AND result = 'SUCCEEDED'
                  AND initiated_at >= now() - make_interval(secs => :lb)
                GROUP BY order_id HAVING count(*) > 1
                """).param("lb", lookbackSeconds)
                .query((rs, i) -> new Violation(rs.getString("entity"), rs.getString("variant")))
                .list();
    }

    /**
     * 三方对账不平（F12 症状）：ENABLED 订单应有 4 类资源 DEDUCT 行，
     * 缺任一类 = 记账与订单状态偏差（数量差型）。
     */
    public List<Violation> reconSkewOrders() {
        return jdbc.sql("""
                SELECT t.id::text AS entity,
                       'deduct-types-' || (SELECT count(DISTINCT l.resource_type)
                            FROM arena.oa_resource_ledger l
                            WHERE l.order_id = t.id AND l.direction = 'DEDUCT') AS variant
                FROM arena.oa_trade_order t
                WHERE t.booking_status = 'ENABLED'
                  AND t.created_at >= now() - make_interval(secs => :lb)
                  AND (SELECT count(DISTINCT l.resource_type)
                       FROM arena.oa_resource_ledger l
                       WHERE l.order_id = t.id AND l.direction = 'DEDUCT') < 4
                """).param("lb", lookbackSeconds)
                .query((rs, i) -> new Violation(rs.getString("entity"), rs.getString("variant")))
                .list();
    }

    /**
     * 库存超卖（F13 症状）：同订单多笔 INVENTORY DEDUCT（正常恰一行；竞态超额行即超卖事实）
     */
    public List<Violation> inventoryOversell() {
        return jdbc.sql("""
                WITH multi AS (
                    SELECT l.order_id
                    FROM arena.oa_resource_ledger l
                      JOIN arena.oa_trade_order t ON t.id = l.order_id
                    WHERE l.resource_type = 'INVENTORY' AND l.direction = 'DEDUCT'
                      AND t.booking_status = 'ENABLED'
                      AND t.created_at >= now() - make_interval(secs => :lb)
                    GROUP BY l.order_id HAVING count(*) > 1
                )
                SELECT l.order_id::text AS entity, 'multi-inventory-deduct' AS variant
                FROM arena.oa_resource_ledger l JOIN multi m ON m.order_id = l.order_id
                WHERE l.resource_type = 'INVENTORY' AND l.direction = 'DEDUCT'
                """).param("lb", lookbackSeconds)
                .query((rs, i) -> new Violation(rs.getString("entity"), rs.getString("variant")))
                .list();
    }

    // ---------- M-a 履约/消息对账事实查询（S21/S22/S25，v2 设计 §3.1） ----------

    /** 履约缺口（F14 症状）：ENABLED 订单无履约记录（消息无痕丢失；近期窗口内） */
    public List<Violation> fulfillmentGapOrders() {
        return jdbc.sql("""
                SELECT t.id::text AS entity, 'no-fulfillment' AS variant
                FROM arena.oa_trade_order t
                WHERE t.booking_status = 'ENABLED'
                  AND t.created_at >= now() - make_interval(secs => :lb)
                  AND NOT EXISTS (SELECT 1 FROM arena.oa_fulfillment_order f
                                  WHERE f.trade_order_id = t.id)
                """).param("lb", lookbackSeconds)
                .query((rs, i) -> new Violation(rs.getString("entity"), rs.getString("variant")))
                .list();
    }

    /** 重复消费（F15 症状）：消费尝试 >1 次的履约单（at-least-once 幂等缺失） */
    public List<Violation> duplicateFulfillments() {
        return jdbc.sql("""
                SELECT f.id::text AS entity, 'multi-attempt' AS variant
                FROM arena.oa_fulfillment_order f
                  JOIN arena.oa_fulfillment_attempt a ON a.fulfillment_id = f.id
                WHERE f.created_at >= now() - make_interval(secs => :lb)
                GROUP BY f.id HAVING count(a.id) > 1
                """).param("lb", lookbackSeconds)
                .query((rs, i) -> new Violation(rs.getString("entity"), rs.getString("variant")))
                .list();
    }

    /** 履约超时（F17 症状）：ENABLED 订单的履约停 CONFIRMING 超龄（SLA 积压；近期窗口内） */
    public List<Violation> fulfillmentOverdue(int olderThanSeconds) {
        return jdbc.sql("""
                SELECT f.id::text AS entity, 'confirming-overdue' AS variant
                FROM arena.oa_fulfillment_order f
                  JOIN arena.oa_trade_order t ON t.id = f.trade_order_id
                WHERE t.booking_status = 'ENABLED' AND f.state = 'CONFIRMING'
                  AND f.created_at < now() - make_interval(secs => :sec)
                  AND f.created_at >= now() - make_interval(secs => :lb)
                """).param("sec", olderThanSeconds)
                .param("lb", lookbackSeconds)
                .query((rs, i) -> new Violation(rs.getString("entity"), rs.getString("variant")))
                .list();
    }

    /** 对账差异数（C2c oa_recon_diff_current 值源）：ENABLED 订单中 DEDUCT 类型不全的行数（近期窗口内） */
    public long reconDiffCount() {
        return jdbc.sql("""
                SELECT count(*) FROM arena.oa_trade_order t
                WHERE t.booking_status = 'ENABLED'
                  AND t.created_at >= now() - make_interval(secs => :lb)
                  AND (SELECT count(DISTINCT l.resource_type)
                       FROM arena.oa_resource_ledger l
                       WHERE l.order_id = t.id AND l.direction = 'DEDUCT') < 4
                """).param("lb", lookbackSeconds).query((rs, i) -> rs.getLong(1)).single();
    }

    /** 对账差异明细（/recon/diffs 端点：差异订单 + 已扣资源类型 + 缺失资源类型） */
    public List<Map<String, Object>> reconDiffDetails() {
        return jdbc.sql("""
                SELECT t.id::text AS order_id, t.sku, t.quantity,
                       COALESCE(string_agg(DISTINCT l.resource_type, ','), '') AS deducted
                  FROM arena.oa_trade_order t
                  LEFT JOIN arena.oa_resource_ledger l
                    ON l.order_id = t.id AND l.direction = 'DEDUCT'
                 WHERE t.booking_status = 'ENABLED'
                   AND t.created_at >= now() - make_interval(secs => :lb)
                 GROUP BY t.id, t.sku, t.quantity
                HAVING count(DISTINCT l.resource_type) < 4
                 ORDER BY t.id
                 LIMIT 200
                """)
                .param("lb", lookbackSeconds)
                .query((rs, i) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("orderId", rs.getString("order_id"));
                    m.put("sku", rs.getString("sku"));
                    m.put("quantity", rs.getInt("quantity"));
                    m.put("deductedTypes", rs.getString("deducted"));
                    return m;
                }).list();
    }

    // ---------- M-a 业务量计数（counter 差值型规则的值源；增量由 DomainProbe 维护） ----------

    /** 订单创建尝试数（含 CREATED——S24/S23 分母） */
    public long countOrdersCreatedSince(java.time.Instant since) {
        return jdbc.sql("""
                SELECT count(*) FROM arena.oa_trade_order WHERE created_at >= :since
                """).param("since", java.sql.Timestamp.from(since))
                .query((rs, i) -> rs.getLong(1)).single();
    }

    /** 订单成功数（ENABLED——S23 分子） */
    public long countOrdersSuccessSince(java.time.Instant since) {
        return jdbc.sql("""
                SELECT count(*) FROM arena.oa_trade_order
                 WHERE enabled_at >= :since
                """).param("since", java.sql.Timestamp.from(since))
                .query((rs, i) -> rs.getLong(1)).single();
    }

    /** 支付成功数（CAPTURE SUCCEEDED——S16 差值左项） */
    public long countPaymentsSuccessSince(java.time.Instant since) {
        return jdbc.sql("""
                SELECT count(*) FROM arena.oa_payment_record
                 WHERE kind = 'CAPTURE' AND result = 'SUCCEEDED' AND settled_at >= :since
                """).param("since", java.sql.Timestamp.from(since))
                .query((rs, i) -> rs.getLong(1)).single();
    }

    /** 履约发起数（S21 差值右项） */
    public long countFulfillmentsStartedSince(java.time.Instant since) {
        return jdbc.sql("""
                SELECT count(*) FROM arena.oa_fulfillment_order WHERE created_at >= :since
                """).param("since", java.sql.Timestamp.from(since))
                .query((rs, i) -> rs.getLong(1)).single();
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
