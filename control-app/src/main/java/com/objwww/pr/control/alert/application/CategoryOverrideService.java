package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.classification.IncidentCategory;
import com.objwww.pr.control.alert.domain.repository.IncidentCategoryRepository;
import com.objwww.pr.control.alert.domain.repository.IncidentCategoryRepository.CategoryState;
import com.objwww.pr.control.alert.domain.repository.IncidentCategoryRepository.OverrideAuditRow;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * UX-01 人工分类 override 命令服务（方案 §六 + UX 迭代方案 §一"人工修正"）：
 * override 与审计行同事务提交；原始 labels/alert_event 不改写；规则重算不覆盖人工值
 * （生效面由 V82 生成列裁决：override 优先，撤销后回落规则面）。
 *
 * <p>裁决序（沿 CommandService 纪律）：幂等重放（同 incident+idempotencyKey 原行返回）
 * → 行锁读决策面 → expectedRevision 锚（不符 → REJECTED_STALE 零副作用）→ CAS 写
 * → insert-only 审计行。理由必填（btrim 非空），actor 取认证主体（controller 截断 64）。
 */
public class CategoryOverrideService {

    public enum State {
        APPLIED, REJECTED_STALE
    }

    /**
     * 命令结果：category/categorySource = 动作后的生效面；revision = 动作后
     * override_revision；replayed=true 表示返回的是既有审计行（未二次生效）。
     */
    public record Result(State state, UUID auditId, String action, String category,
                         String categorySource, int revision, boolean replayed) {
    }

    private final IncidentCategoryRepository categories;
    private final TransactionOperations tx;
    private final Supplier<Instant> now;

    public CategoryOverrideService(IncidentCategoryRepository categories,
                                   TransactionOperations tx, Supplier<Instant> now) {
        this.categories = Objects.requireNonNull(categories, "categories");
        this.tx = Objects.requireNonNull(tx, "tx");
        this.now = Objects.requireNonNull(now, "now");
    }

    /**
     * 确立/改判 override。incident 不存在 → empty（404 面）。
     * category 非法 / reason 空白 / expectedRevision 或 idempotencyKey 缺失
     * → IllegalArgumentException（400 面）。
     */
    public Optional<Result> override(UUID incidentId, String categoryRaw, String reason,
                                     Integer expectedRevision, String idempotencyKey,
                                     String actor) {
        IncidentCategory category = IncidentCategory.parse(categoryRaw)
                .orElseThrow(() -> new IllegalArgumentException(
                        "category 非法（词表见 IncidentCategory）: " + categoryRaw));
        return execute(incidentId, reason, expectedRevision, idempotencyKey, actor,
                category.name());
    }

    /** 显式撤销 override（审计动作 REVOKE）；当前无 override → IllegalArgumentException */
    public Optional<Result> revoke(UUID incidentId, String reason, Integer expectedRevision,
                                   String idempotencyKey, String actor) {
        return execute(incidentId, reason, expectedRevision, idempotencyKey, actor, null);
    }

    private Optional<Result> execute(UUID incidentId, String reason, Integer expectedRevision,
                                     String idempotencyKey, String actor, String toCategory) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason 必填（非空白）");
        }
        if (expectedRevision == null) {
            throw new IllegalArgumentException("expectedRevision 必填");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey 必填");
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor 缺失（认证面缺陷，不放行）");
        }
        return tx.execute(status -> apply(incidentId, reason.strip(), expectedRevision,
                idempotencyKey, actor, toCategory));
    }

    private Optional<Result> apply(UUID incidentId, String reason, int expectedRevision,
                                   String idempotencyKey, String actor, String toCategory) {
        // 1. 幂等重放：原审计行原样结算（不重写 incident 面）
        Optional<OverrideAuditRow> replay =
                categories.findAuditByIdempotencyKey(incidentId, idempotencyKey);
        if (replay.isPresent()) {
            OverrideAuditRow row = replay.get();
            return Optional.of(new Result(State.APPLIED, row.id(), row.action(),
                    row.toCategory(),
                    row.toCategory() != null ? "OVERRIDE" : "RULE",
                    row.resultRevision(), true));
        }

        // 2. 行锁读决策面；incident 不存在 → 404
        CategoryState state = categories.lockState(incidentId).orElse(null);
        if (state == null) {
            return Optional.empty();
        }

        // 3. 修订锚（零副作用拒绝）
        if (state.overrideRevision() != expectedRevision) {
            return Optional.of(new Result(State.REJECTED_STALE, null,
                    toCategory != null ? "SET" : "REVOKE", null, null,
                    state.overrideRevision(), false));
        }
        boolean set = toCategory != null;
        if (!set && state.overrideCategory() == null) {
            throw new IllegalArgumentException("当前无人工 override 可撤销");
        }

        // 4. CAS 写（行锁下必成；false = 防御面）
        boolean written = set
                ? categories.setOverride(incidentId,
                        IncidentCategory.valueOf(toCategory), actor, reason,
                        now.get(), expectedRevision)
                : categories.clearOverride(incidentId, expectedRevision);
        if (!written) {
            return Optional.of(new Result(State.REJECTED_STALE, null,
                    set ? "SET" : "REVOKE", null, null, expectedRevision, false));
        }

        // 5. 审计行（同事务）；from = 动作前生效面快照
        int resultRevision = expectedRevision + 1;
        OverrideAuditRow audit = new OverrideAuditRow(UUID.randomUUID(), incidentId,
                set ? "SET" : "REVOKE", state.effectiveCategory(), toCategory,
                actor, reason, expectedRevision, resultRevision, idempotencyKey, now.get());
        categories.appendAudit(audit);

        String effectiveAfter = set ? toCategory
                : (state.ruleCategory() != null ? state.ruleCategory()
                        : IncidentCategory.UNCLASSIFIED.name());
        return Optional.of(new Result(State.APPLIED, audit.id(), audit.action(),
                effectiveAfter, set ? "OVERRIDE" : "RULE", resultRevision, false));
    }
}
