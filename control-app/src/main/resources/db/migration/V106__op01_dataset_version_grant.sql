-- OP-01 入集写面授权补齐（195 真机实证）：materialize 以 control_app 身份写
-- V20 不可变版本表 DatasetVersion/CaseVersion——V20 只授了 eval_app（导入侧），
-- 该写路径首次接 control_app 生产面（读路径/eval 面授权不变）。不可变版本表
-- 授 select+insert，无 update/delete（INV-AM5-1 append-only 版本语义）。
grant select, insert on dataset_version, case_version to control_app;
