-- ============================================================================
-- V23 —— AM5 模型采样指纹（M5-04；docs/告警AM5-落码技术方案.md §M5-04② 方案甲）
--   rca_attempt.sampling_fingerprint jsonb
--     每 Attempt 的生效采样参数回写落库（FUT-40/INV-AM5-3）：temperature/
--     top_p/max_tokens/seed 请求态+生效态两态分列于 jsonb 内 + provider
--     fingerprint/model/trial_no。eval_run（V10）已有 run 头粒度同名元数据，
--     本迁移把它下沉到 attempt 粒度并加两态分离。
--
-- 键集 DB 兜底（ck_rca_attempt_fingerprint_keys）：指纹行必须带全五顶层键
--   requested/effective/provider_fingerprint/model/trial_no——键缺失即违约；
--   字段级完整性（两态各四字段非空 + trial >= 1）是门禁消费面语义，由域面
--   SamplingFingerprint.isGateEligible 把门（INV-AM5-3：缺字段不得进入正式
--   门禁）——jsonb 键存在性检查对嵌套 null 值不过问（诚实留空 ≠ 缺键）。
--
-- qwen3.7-plus 是 thinking 型（交接文档 §四）：max_tokens 过小会被思考耗尽、
--   reasoning_tokens 计入 completion_tokens——指纹必须记录 max_tokens 生效值。
--
-- 授权说明：rca_attempt 的写方是 control_app（V7 已授表级 select,insert,update），
--   新列随表级 UPDATE 自动可达，本迁移不新增授权面；eval_app 等其余角色对本表
--   零授权不变。
--
-- 编号：落码方案迁移表 V23（一迁移一任务，INV-AM5-10）。
-- ============================================================================

alter table rca_attempt add column sampling_fingerprint jsonb;

comment on column rca_attempt.sampling_fingerprint is
    'AM5 采样指纹（M5-04，FUT-40/INV-AM5-3）：LLM 调用点回写的每 Attempt 生效采样参数——requested/effective 两态 {temperature,top_p,max_tokens,seed} + provider_fingerprint + model + trial_no；键集由 ck_rca_attempt_fingerprint_keys 兜底，字段级完整性由 SamplingFingerprint.isGateEligible 把门（缺字段不进正式门禁）';

alter table rca_attempt
    add constraint ck_rca_attempt_fingerprint_keys
    check (sampling_fingerprint is null
        or (sampling_fingerprint ? 'requested'
            and sampling_fingerprint ? 'effective'
            and sampling_fingerprint ? 'provider_fingerprint'
            and sampling_fingerprint ? 'model'
            and sampling_fingerprint ? 'trial_no'));
