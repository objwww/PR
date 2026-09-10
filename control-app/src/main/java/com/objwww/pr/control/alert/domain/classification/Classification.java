package com.objwww.pr.control.alert.domain.classification;

import java.util.Objects;

/**
 * 一次分类裁决（UX-01）：类别 + 稳定 ruleId + 规则版本 + 人类可读命中依据。
 *
 * <p>无任何概率/置信度字段——规则命中只留依据（§六："未校准权重称规则优先级/命中依据，
 * 不展示 0.93 概率"）。ruleVersion 随规则表 Git 审查演进，首期不热更。
 */
public record Classification(IncidentCategory category, String ruleId,
                             String ruleVersion, String basis) {
    public Classification {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(ruleVersion, "ruleVersion");
        Objects.requireNonNull(basis, "basis");
    }
}
