package com.objwww.pr.control.alert.domain.claim;

/**
 * 断言四类型（F06 存储契约；语义源 = 全系统收口主计划 §8.4，冻结）：
 * <ul>
 *   <li>SYMPTOM —— 已观察异常（指标/日志交叉确认即可成立）；</li>
 *   <li>HYPOTHESIS —— 待验证原因（未满足 ROOT_CAUSE 核验条件前的一切解释；
 *       <b>无类型断言的缺省形态</b>——缺省禁 ROOT_CAUSE，即"症状自动升级为根因"
 *       的结构性禁止）；</li>
 *   <li>ROOT_CAUSE —— 根因（因果机制解释 + 必要现场证据）；</li>
 *   <li>EXCLUSION —— 已排除方向。</li>
 * </ul>
 *
 * <p>分工裁定（评审 P1-01）：<b>类型准入与转换逻辑（Observation→Claim 转换、
 * 类型语义门、Holmes 输出→typed Claim 转换）归 R7c 单一责任人</b>——A 线
 * （EX-A4a）只交本枚举与 rca_claim.kind 存储列，不写任何转换逻辑。
 *
 * <p>kind 不进 claim_fingerprint/contentHash 双哈希（M4-21/22 契约维持——回放
 * 比对稳定）；kind 是准入元数据非内容身份，如需入哈希由 R7c 升版本，不原地改义。
 */
public enum ClaimKind {
    SYMPTOM,
    HYPOTHESIS,
    ROOT_CAUSE,
    EXCLUSION
}
