package com.objwww.pr.control.alert.domain.classification;

import java.util.Optional;

/**
 * UX-01 告警分类词表（docs/告警-评测中心与能力版本演进-审查及详细改造方案-v1.md §六）：
 * 7 个业务类 + PLATFORM / UNCLASSIFIED 两个独立出口。
 *
 * <p>纪律：
 * <ul>
 *   <li>PLATFORM（控制面自身异常）与 UNCLASSIFIED（信息不足）独立于 7 个业务类，
 *       不参与业务类排序，UNCLASSIFIED 不得默认视为平台异常；</li>
 *   <li>分类只描述告警所属面，不预断根因；severity 与分类独立（INV：升级不换类）；</li>
 *   <li>词表与 V82 CHECK 约束一一对应——改词表 = 改枚举 + 新迁移。</li>
 * </ul>
 */
public enum IncidentCategory {
    /** 订单失败率/SLO/交易损失等业务指标告警 */
    BUSINESS,
    /** 异常/延迟/状态机/进程（应用自身） */
    APPLICATION,
    /** DB/MQ/缓存/下游 API */
    DEPENDENCY,
    /** 主机/容器/CPU/内存/磁盘 */
    INFRA,
    /** 连接/DNS/TLS/丢包 */
    NETWORK,
    /** 数据质量/新鲜度/流水线 */
    DATA,
    /** 越权/泄露/策略违规 */
    SECURITY,
    /** 控制面自身异常（独立于 7 个业务类） */
    PLATFORM,
    /** 尚无足够信息归类的显式出口（不是"还没跑分类器"——那是 rule_category IS NULL） */
    UNCLASSIFIED;

    /** 非法词 → empty（controller 400 面）；大小写敏感与 CHECK 约束一致 */
    public static Optional<IncidentCategory> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(IncidentCategory.valueOf(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
