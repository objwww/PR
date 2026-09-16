-- ============================================================================
-- V10 —— M-a：ck_inj_fault_type 扩到业务故障族（F9~F18）
--
-- 同 V9 的遗漏面：oa_injection_audit.fault_type CHECK 仍是 F1~F3，业务族恢复
-- 收口的会话级 RECOVERED 审计 INSERT 被拒（T8 演练捕获，恢复循环每轮重试告警）。
-- ============================================================================

alter table arena.oa_injection_audit drop constraint ck_inj_fault_type;

alter table arena.oa_injection_audit add constraint ck_inj_fault_type
    check (fault_type in ('F1','F2','F3',
                          'F9','F10','F11','F12','F13',
                          'F14','F15','F16','F17','F18'));
