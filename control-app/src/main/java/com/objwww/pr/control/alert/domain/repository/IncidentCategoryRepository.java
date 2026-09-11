package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.classification.IncidentCategory;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * UX-01 告警分类写面端口（incident 分类列 + incident_category_override 审计表）。
 *
 * <p>两族列分离（方案 §六"规则重算不覆盖人工值"）：
 * <ul>
 *   <li>{@link #applyRuleClassification} 只写 rule_* 四列——投影路径每次重分类都允许，
 *       override_* 列不在其 SQL 内，人工值物理上不可能被规则覆盖；</li>
 *   <li>override 面经 {@link #lockState}（行锁）→ CAS 写（override_revision 锚）
 *       → {@link #appendAudit}（insert-only 审计行）三步，同事务提交；</li>
 *   <li>生效面（category/category_source）是 V82 STORED 生成列，应用层不可写。</li>
 * </ul>
 */
public interface IncidentCategoryRepository {

    /** override 决策快照（行锁读取）：规则面/人工面/生效面 + 乐观并发锚 */
    record CategoryState(String ruleCategory, String overrideCategory,
                         String effectiveCategory, int overrideRevision) {
    }

    /** 审计行（insert-only）：谁/何时/从哪类到哪类/理由/期望与结果 revision/幂等键 */
    record OverrideAuditRow(UUID id, UUID incidentId, String action,
                            String fromCategory, String toCategory,
                            String actor, String reason,
                            int expectedRevision, int resultRevision,
                            String idempotencyKey, Instant createdAt) {
    }

    /**
     * 规则重分类（投影路径；非重复且在 episode 水印内的事件触发）。
     * 只写 rule_category/category_rule_id/category_rule_version/category_classified_at。
     */
    void applyRuleClassification(UUID incidentId, IncidentCategory category,
                                 String ruleId, String ruleVersion, Instant classifiedAt);

    /** 行锁读 override 决策面；incident 不存在 → empty（controller 404 面） */
    Optional<CategoryState> lockState(UUID incidentId);

    /** 确立/改判 override（CAS：override_revision=:expectedRevision 才生效，false=冲突） */
    boolean setOverride(UUID incidentId, IncidentCategory category, String actor,
                        String reason, Instant at, int expectedRevision);

    /** 显式撤销 override（CAS 同上）；生效面回落规则分类 */
    boolean clearOverride(UUID incidentId, int expectedRevision);

    /** 追加审计行（uq(incident_id, idempotency_key) 为最终幂等防线） */
    void appendAudit(OverrideAuditRow row);

    /** 幂等重放探测：同键既有审计行原样返回 */
    Optional<OverrideAuditRow> findAuditByIdempotencyKey(UUID incidentId, String idempotencyKey);
}
