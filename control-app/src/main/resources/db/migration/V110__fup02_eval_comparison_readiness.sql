-- ============================================================================
-- V110 —— FUP-02 评测对比质量门 v2：证据就绪度快照列
--   （docs/SAFE闭面后-剩余边界与开源对照复核-20260914.md §3 FUP-02 "怎么改" 6）
--   eval_comparison.readiness_snapshot   门规则 v2 落档的证据就绪度快照（jsonb 原文：
--                                        规则版本/阈值/运行终态/计划分母 expected/
--                                        completed/missing/身份核验计数与缺失清单）
--
-- 语义纪律（V85 同律）：
--   - 只加可空列：v1 历史行保持 NULL，不回填不改写——历史审计保留原始结论与
--     规则版本（gate_rule_version 列区分 v1/v2）；
--   - NULL 还可表示"可比性未过"面（不出配对结论即无就绪度核算）；
--   - 授权面无变化（列级沿用 V85 表级 select,insert）。
-- ============================================================================

alter table eval_comparison add column readiness_snapshot jsonb;

comment on column eval_comparison.readiness_snapshot is
    'FUP-02 证据就绪度快照（gate v2 落档；NULL = v1 历史行或可比性未过面，不回填）';
