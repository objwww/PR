-- PA-A3（Phase A 第三片）：alert_inbox 隔离区——QUARANTINED 第七态。
--
-- 设计基线：docs/告警-Agent授权-方案Bv2严格执行-全量纳入设计-v2.md §4 L0
-- （矩阵 L0-4/L0-5：injection 扫描 + 恶意告警隔离区）。
-- 隔离语义：intake 扫描命中（schema 校验之后、落库之前）→ 初始态直插 QUARANTINED，
-- 零业务处理（claim 面 SQL 只领 RECEIVED/RETRY_WAIT，结构上不可达）；隔离原因
-- 落 last_error jsonb（reason=PROMPT_INJECTION + 命中特征清单）；202 应答不变
-- （持久化即受理，AM 不重试）。人工复核放行 = QUARANTINED→RECEIVED（状态机已备，
-- 放行入口随 AM8 管理面交付）。
--
-- 授权说明：alert_inbox 写方 control_app（V7 已授 update），无新授权。

alter table alert_inbox drop constraint ck_alert_inbox_state;
alter table alert_inbox add constraint ck_alert_inbox_state
    check (state in ('RECEIVED','PROCESSING','RETRY_WAIT','PROCESSED','IGNORED',
                     'DEAD_LETTER','QUARANTINED'));

comment on constraint ck_alert_inbox_state on alert_inbox is
    'PA-A3：QUARANTINED = 注入扫描隔离区（L0-5）——初始态直插，claim 面不可达，'
    '人工放行走 QUARANTINED→RECEIVED（入口随 AM8）';
