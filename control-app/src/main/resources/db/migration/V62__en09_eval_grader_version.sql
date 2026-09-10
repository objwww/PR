-- ============================================================================
-- V62 —— EN-09 评测补强：评分器版本入 eval_run 身份面（增强线方案 §十二 EN-09 卡，
--   E11 历史分数归属面：换评分器重评分 = 新记录，不覆盖原证明；批次身份
--   config_digest 由 EvalRunMetadata canonical map 计算，grader_version 参与）。
--
--   历史批次留空 = EN-09 前评分器版本未入账（"未配置可区分"既有约定，
--   不可冒充新版证据）；生产批次经 app.alert.eval.grader-version 必填注入
--   （EvalRunnerConfig fail-closed）。
--
-- 号段：EN=V60 起（2026-09-11 三线定死，增强线方案 §6.1）。
-- ============================================================================

alter table eval_run add column grader_version text;

comment on column eval_run.grader_version is
    'EN-09 评分器版本（E11）：历史分数按版本归属；null = EN-09 前历史批次，可区分不可冒充';
