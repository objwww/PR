-- ============================================================================
-- V45 —— UI-5 评测只读投影授权（/api/eval/** 后端面）
--
-- 背景：V10/V20/V21 把评测域五表（eval_run/eval_case_result/dataset_version/
--   case_version/case_family_partition）对 control_app 显式 revoke 归零
--   （评测面与生产面隔离，eval_app 独立身份）。UI-5 评测查询投影由 control-app
--   以 control_app 身份只读投影，需回收最小读面。
--
-- 纪律：
--   - 只授 SELECT——零写开口（insert/update/delete 仍只属于 eval_app）；
--   - 不建表不改表；
--   - case_version 有 RLS（V21 enable row level security）：只 grant 不够——
--     无策略角色默认拒绝恒 0 行。此处为 control_app 补一条 select 策略，
--     谓词与评分身份 eval_app 同构（partition_class <> 'HOLDOUT'）：
--     调优界面可见 TUNING/VALIDATION/REDTEAM，HOLDOUT 封存面依旧不可见
--     （INV-AM5-1 纵深不因 UI 投影开口而破坏）。
--     dataset_version/case_family_partition 无 RLS，grant 即生效。
-- ============================================================================

grant select on eval_run, eval_case_result, dataset_version, case_version,
    case_family_partition to control_app;

-- control_app 的 case_version 行级可视面 = 评分身份同构（非 HOLDOUT）
create policy pol_case_version_control_app_select on case_version
    for select to control_app
    using (partition_class <> 'HOLDOUT');
