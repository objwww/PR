package com.objwww.pr.control.drill.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * DR-05 flagd 恢复台账行（V95 flagd_restore_ledger 的域形；方案
 * docs/告警-前端逐页体验改造与后期优化方案.md §7.4 Flagd 段「记录修改前值和版本，
 * 只在当前值仍属于本次写入时条件恢复」+ §7.5 DR-05 卡「保存实际原值与写入版本、
 * 条件恢复、冲突不覆盖、worker 重启后清扫」）：
 * <ul>
 *   <li>激活时落 OPEN：实际原值/原代际（读取失败 = null，如实记录）+ 本次写入值/
 *       写入代际 + 作业级截止（preheat+hold+firingWait+resolvedWait+cleanup）；</li>
 *   <li>条件恢复三态收口：RESTORED（当前值仍属本次写入且已写回）/ CONFLICT（他者
 *       已改写，不覆盖、不宣称恢复成功）/ UNKNOWN（读不到当前状态，不盲写——
 *       保持可恢复面，超 deadline 由 FlagdRestoreSweeper 重试）；</li>
 *   <li>恢复目标 = 实际原值优先（{@link #restoreTarget()}），激活时读不到原值才以
 *       模板 baseline 兜底。</li>
 * </ul>
 */
public record FlagdRestoreRecord(
        UUID id,
        String flag,
        String scenarioId,
        int roundNo,
        String originalVariant,
        String originalGeneration,
        String appliedVariant,
        String appliedGeneration,
        String baselineVariant,
        Instant deadlineAt,
        State state,
        String stateReason,
        Instant createdAt,
        Instant updatedAt) {

    /** 台账状态：OPEN/UNKNOWN = 可恢复面（清扫重试入口）；RESTORED/CONFLICT = 终态 */
    public enum State {
        OPEN, RESTORED, CONFLICT, UNKNOWN;

        public boolean isRestorable() {
            return this == OPEN || this == UNKNOWN;
        }
    }

    public FlagdRestoreRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(flag, "flag");
        Objects.requireNonNull(scenarioId, "scenarioId");
        Objects.requireNonNull(appliedVariant, "appliedVariant");
        Objects.requireNonNull(baselineVariant, "baselineVariant");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** 恢复目标：实际原值优先（§7.4「记录修改前值」）；原值缺失 = 模板 baseline 兜底 */
    public String restoreTarget() {
        return originalVariant != null ? originalVariant : baselineVariant;
    }
}
