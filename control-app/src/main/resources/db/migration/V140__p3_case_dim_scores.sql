-- ============================================================================
-- V140 —— P3 RCA 三维评分+过程计数落 eval_case_result（对标 RCA-Bench
-- 定因/定界路径/结论复核三维协议的确定性适配版；全列可空=该维未评，不填 0）
--
-- 三维口径（确定性规则，无 LLM-judge——与 ScenarioMetrics 拒评纪律同律）：
--   定因 Cause      = 期望/实际根因三元组的逐维同义词命中（component/fault/reason
--                     三布尔；全中=root_cause_hit，部分中=部分分——图距离评分的
--                     三元组适配）。
--   路径 Path       = GT 证据检查点覆盖（checkpoints_total/covered + 逐点命中
--                     jsonb；检查点由 materialize 时人工显式提供——RCA-Bench
--                     evidence_checkpoints 同构）。null total = 该案例无检查点。
--   结论复核        = 结论有据性 conclusion_grounded：结论为 TRUE 的根因 claim
--                     是否携带证据引用（OpenRCA 2.0 ungrounded diagnosis 探针；
--                     GROUNDED/UNGROUNDED/NOT_APPLICABLE）。
-- 过程 Process 计数 = 评分 attempt 的 tool_call 账本计数（total/unique——
--                     重复调用率的过程维观测；RCAEval 过程层同题）。
--
-- 纪律：全部可空（历史行/未评形态如实 null）；eval_app 表级 INSERT 已授（V10），
--       新可空列无需补授权；无 UPDATE 路径，落库即冻结。
-- ============================================================================

alter table eval_case_result
    add column cause_component_hit boolean,
    add column cause_fault_hit     boolean,
    add column cause_reason_hit    boolean,
    add column checkpoints_total   integer,
    add column checkpoints_covered integer,
    add column checkpoint_matches  jsonb,
    add column conclusion_grounded text,
    add column tool_calls_total    integer,
    add column tool_calls_unique   integer;

comment on column eval_case_result.cause_component_hit is
    'P3 定因维：根因 component 同义词命中（null=未评，DECIDABLE 才有值）';
comment on column eval_case_result.checkpoint_matches is
    'P3 路径维：逐检查点命中明细 jsonb（[{"checkpoint","matched"}]；null=无检查点未评）';
comment on column eval_case_result.conclusion_grounded is
    'P3 结论复核维：TRUE 根因 claim 是否带证据引用 GROUNDED/UNGROUNDED/NOT_APPLICABLE（null=未评）';
