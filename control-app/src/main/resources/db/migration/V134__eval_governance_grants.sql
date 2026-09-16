-- ============================================================================
-- V134 —— 3.11 评测治理补授权：V10 给 control_app 的是列级 UPDATE（state/计数等引擎列），
-- V133 新增的治理三列不在其列——update 即 permission denied（经 /error 转发被 denyAll
-- 映射成 403，与 UI-DATA 系列的掩蔽同款）。列级授权按列追加即可，不动 V10 已授权面。
-- ============================================================================

grant update (governance_tag, governance_tagged_by, governance_tagged_at)
    on eval_run to control_app;
