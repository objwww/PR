-- ============================================================================
-- V143 —— P6-G8 难度分层：eval_case_result.difficulty（RCA-Bench L1–L4 口径）
--
-- 难度标注属案例定义面（GoldenCase 双载体：YAML difficulty 键 / 数据集案例
-- rawArtifact 保留键 gt_difficulty），评分时随案例行落档——读面按难度聚合
-- （L1–L4 分层报表）与 SMOKE panel 快速回归共同构成 G8。
-- 可空 = 未标注（历史行/未标案例如实 null，不填 0）；无 UPDATE 路径，落库即冻结；
-- eval_app 表级 INSERT 已授（V10），新可空列无需补授权（V140 同律）。
-- ============================================================================

alter table eval_case_result
    add column difficulty text;

comment on column eval_case_result.difficulty is
    'P6-G8 难度分层：RCA-Bench L1–L4（案例定义面标注随评分落档；null=未标注）';
