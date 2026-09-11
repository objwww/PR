-- ============================================================================
-- V88 —— R7 反馈环：rca_primary_checkpoint.last_error（A0 八跑实证解锁项）
--   上一步可重试失败（DECISION_UNPARSEABLE/TOOL_NOT_ALLOWED/TOOL_RETRYABLE:*/
--   INVALID_ARGS）的原因与修正指引持久化在检查点上，随下一步信封 last_error
--   面回喂模型——八跑实证盲重驱=模型连猜同错（logs.query INVALID_ARGS×4 无
--   修正依据）；成功步清空。崩溃恢复后重驱动信封同样携带（反馈不丢）。
--   号段：V87 已由 EV-08（ev/eval-center 分支）占用，本卡取 V88。
-- ============================================================================

alter table rca_primary_checkpoint add column last_error text;

comment on column rca_primary_checkpoint.last_error is
    'R7 反馈环（V88）：上一步可重试失败的原因与修正指引，随信封 last_error 面回喂模型；成功步清空（NULL）';
