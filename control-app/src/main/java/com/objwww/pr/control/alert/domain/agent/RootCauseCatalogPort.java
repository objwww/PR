package com.objwww.pr.control.alert.domain.agent;

import java.util.List;
import java.util.Objects;

/**
 * 主 Agent 根因码表读口（告警-Agent 根因评分贯通修复）：把评测词表
 * （synonym-lexicon，root_cause_hit 的唯一判定面）的 canonical 码确定性投影为
 * 模型可读的根因候选清单，随信封 {@code root_cause_catalog} 键入模——
 * ROOT_CAUSE claim 的 root_cause 三元组取值只能来自本表 canonical 码
 * （component/fault_type/reason_code），模型输出 canonical 码即可命中评分
 * （synonyms 是评分侧容差，不下发）。
 *
 * <p>码表是提示面不是校验面：解析器（{@link PrimaryDecision}）只管三元组形状，
 * 取值是否落在码表内由评分器裁决（miss 如实记分，不做控制面拒绝）。
 * 实现缺失/加载失败 → 空表（信封不放该键），不阻断告警主链（告警可用性优先）。
 */
public interface RootCauseCatalogPort {

    /** 空码表（词表缺失/解析失败的诚实降级面：信封省略 root_cause_catalog 键） */
    RootCauseCatalogPort EMPTY = List::of;

    /** 每场景一行 canonical 码（component/fault_type/reason_code + 机制描述） */
    List<Entry> entries();

    /**
     * 码表条目：canonical 三元组 + 描述。三码非空（空码不教模型——教了也恒 miss）；
     * description 可空串（词表缺描述如实空，不造数）。
     */
    record Entry(String component, String faultType, String reasonCode,
            String description) {
        public Entry {
            if (component == null || component.isBlank()) {
                throw new IllegalArgumentException("component 必须是非空字符串");
            }
            if (faultType == null || faultType.isBlank()) {
                throw new IllegalArgumentException("faultType 必须是非空字符串");
            }
            if (reasonCode == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("reasonCode 必须是非空字符串");
            }
            description = Objects.requireNonNull(description, "description");
        }
    }
}
