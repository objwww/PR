-- ============================================================================
-- V150 —— DR-04 演练恢复执行链：人工重试意图列（docs/告警-前端逐页体验改造与后期优化
--   方案.md §7.4「RECOVERY_FAILED→RECOVERING 人工核验后重试恢复」；DR-04 卡）
--
-- 语义纪律（对称 V86 停止面）：
--   - retry_requested_at = 人工重试意图（受理≠已推进）：control_app 只能提交意图，
--     RECOVERY_FAILED→RECOVERING 推进归 eval_app worker（consumeRetry 单语句 CAS +
--     清意图 + 刷新租约）——HTTP 线程状态机推进零开口纪律不破；
--   - 意图按行幂等（重复重试只置位一次），无跨行键占用面，故不需要幂等键列；
--   - drill_event.event_type 放行 RETRY_REQUESTED（重试请求审计行，对称
--     STOP_REQUESTED）。
-- ============================================================================

alter table drill_job add column retry_requested_at timestamptz;

alter table drill_event drop constraint drill_event_event_type_check;
alter table drill_event add constraint drill_event_event_type_check
    check (event_type in ('PHASE_TRANSITION', 'PRECHECK_RESULT', 'STOP_REQUESTED',
        'RETRY_REQUESTED', 'OUTCOME_RECORDED', 'WORKER_NOTE'));

-- control_app：重试意图列写面（状态机推进零开口不变）
grant update (retry_requested_at) on drill_job to control_app;
-- eval_app（worker）：消费重试意图（推进时清列）
grant update (retry_requested_at) on drill_job to eval_app;
