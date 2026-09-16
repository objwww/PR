-- ============================================================================
-- V9 —— M-a：ck_chaos_fault_type 扩到业务故障族（F9~F18）
--
-- T1 只放宽了 arena-chaos-admin 的激活校验正则（F1~F3 / F9~F18），DB CHECK 未同步——
-- T8 真栈演练首日捕获：F9 激活撞 ck_chaos_fault_type 被控制器转成 409
-- "scenario 已存在或同型同靶会话仍活跃"，掩蔽了真实原因。
-- 本迁移把 DB 面对齐 Java 校验面；F4~F8（M-c C1 技术族）届时再扩。
-- ============================================================================

alter table arena.oa_chaos_session drop constraint ck_chaos_fault_type;

alter table arena.oa_chaos_session add constraint ck_chaos_fault_type
    check (fault_type in ('F1','F2','F3',
                          'F9','F10','F11','F12','F13',
                          'F14','F15','F16','F17','F18'));
