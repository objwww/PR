package com.objwww.pr.shared;

/**
 * publication_resource 观测态（v2.2 §1 + M1 方案 §4.6 措辞修正 #2，与 V3 ck_pub_resource_state 一致）。
 * 资源视角的"现在时"，与 Outbox 命令历史分离；漂移观测永不进入 Outbox。
 *
 * <p>语义（M1-T08 迁移：ACTIVE→PRESENT、DRIFTED→MISSING、新增 UNKNOWN）：
 * <ul>
 *   <li>PRESENT：已确认存在（CONFIRMED 登记 / DriftReconciler 探针命中）；</li>
 *   <li>MISSING：探针 404 且 sanity 读通过（F-3：404 本身无法区分"不存在"与"无权限"，
 *       必须经 repo 级 sanity 读确认 token/权限/仓库可达才允许标 MISSING）；</li>
 *   <li>UNKNOWN：未巡检或无法判定（sanity 失败——权限异常绝不冒充"不存在"，E2E-18）；</li>
 *   <li>RETIRED/REPAIRED：保留 M0 原义，不参与 DriftReconciler 巡检。</li>
 * </ul>
 */
public enum PublicationResourceState {

    PRESENT,
    MISSING,
    UNKNOWN,
    REPAIRED,
    /** 旧世代自身对象被正常关闭（如旧 Check 标 cancelled） */
    RETIRED
}
