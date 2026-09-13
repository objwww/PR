package com.objwww.pr.control.alert.domain.model;

/**
 * Run 准入身份（SR §3.1）：影子/评测/生产分开，发布准入从该持久身份判断，
 * 不信任调用者"不调用 notifier"的约定。
 *
 * <p>LEGACY_UNKNOWN = V108 之前的存量行（purpose 列 NULL，读侧 coalesce）——
 * 历史来源无法证明时不得标 PRODUCTION，禁止凭 run 名字前缀推断身份。
 */
public enum RunPurpose {
    PRODUCTION,
    SHADOW,
    EVAL,
    LEGACY_UNKNOWN
}
