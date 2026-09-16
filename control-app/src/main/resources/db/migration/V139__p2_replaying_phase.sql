-- ============================================================================
-- V139 —— P2 回放阶段词：eval_phase_event 阶段词表扩 REPLAYING
--
-- 195 E2E 实证（2026-09-16）：REPLAYING 落 ck_eval_phase_event_phase 拒绝——
-- 词表是 V80 冻结面，新执行形态（回放激活）的观测词须随迁移入册，不词表外私用。
-- 回放语义：REPLAYING = 冻结载荷重投激活（与 INJECTING 的故障注入并列的两激活形态）。
-- ============================================================================

alter table eval_phase_event drop constraint ck_eval_phase_event_phase;

alter table eval_phase_event add constraint ck_eval_phase_event_phase check (phase in
    ('PREPARING','INJECTING','REPLAYING','AWAITING_ALERT','AWAITING_RCA',
     'SCORING','FINALIZING','RECOVERING'));
