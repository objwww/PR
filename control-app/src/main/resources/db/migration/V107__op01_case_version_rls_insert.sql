-- OP-01 入集写面 RLS 补齐（195 真机实证续）：V21 给 case_version 立了分区
-- RLS 矩阵——control_app 只有非 HOLDOUT 读策略，无 INSERT 策略（materialize
-- 写 case_version 被 RLS 拒）。补一条与 eval_app 同式的插入策略：封存门
-- HOLDOUT 仍对应用侧全员（含 control_app）不可写——HOLDOUT 案例只能经 eval
-- 导入面（owner 身份）落库，保持封存语义不变。
create policy pol_case_version_control_app_insert on case_version
    for insert to control_app
    with check (partition_class <> 'HOLDOUT');
