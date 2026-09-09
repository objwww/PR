-- V35 —— BA-60 / M6-07（C-77）：canary_route_decision.run_id 摘 NOT NULL。
-- 退场后 HOLMES 意愿/止损类决策照记但不铸 run（技术方案 §4.4 无写入入口不变量）——
-- 决策行携带铸造点预生成 runId 而 run 行永不落库时，V31 deferred FK 在提交点必 23503
-- （幽灵引用，BA-53 同型）。审计归属面语义：run_id 非空 = 该决策随 run 落库（NATIVE
-- 出路）；run_id 为空 = 决策照记且无 run 生成（BUCKETED_HOLMES / CANARY_DISABLED /
-- NO_STICKINESS_KEY / NATIVE_DEFERRED / BLAST_RADIUS_STOPPED）。FK 约束本体不动
-- （NULL 不经 FK 检查，DEFERRABLE INITIALLY DEFERRED 语义保持 V31 原样）。
alter table canary_route_decision alter column run_id drop not null;
