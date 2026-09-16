-- ============================================================================
-- V12 —— M-a：ck_probe_finding_type 扩到业务 episode 类型（F9~F17 对应 8 类）
--
-- 与 V9/V10 同族遗漏：oa_probe_finding.finding_type CHECK 仍是 M2 老三型——
-- 业务族 episode 开户 INSERT 撞约束，探测面进 C-7 失明（保留末值、告警永不 firing）。
-- T8 真栈演练 2026-09-18 21:08:49 日志捕获。
-- ============================================================================

alter table arena.oa_probe_finding drop constraint ck_probe_finding_type;

alter table arena.oa_probe_finding add constraint ck_probe_finding_type
    check (finding_type in ('STUCK_ORDER','DUPLICATE_ORDER','STATE_VIOLATION',
                            'PAYMENT_ORDER_MISMATCH','PENDING_PAYMENT',
                            'DUPLICATE_PAYMENT','RECON_SKEW','INVENTORY_OVERSELL',
                            'FULFILLMENT_GAP','DUPLICATE_FULFILLMENT','FULFILLMENT_OVERDUE'));
