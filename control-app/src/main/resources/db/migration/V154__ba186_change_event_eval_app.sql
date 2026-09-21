-- BA-186/S27：eval_app 对 change_event 补 select+insert——S27 变更回归场景的
-- 发布事实落账由评测 worker（eval 身份）内 FlagdScenarioDriver 经 ChangeEventLedger
-- 执行（V40 仅授 control_app/deploy_app，eval_app 零权限致注入判败：生效而无证据）。
-- 授权面严格对齐 control_app 既有面（select+insert，无 update/delete——append-only）。
GRANT SELECT, INSERT ON change_event TO eval_app;
