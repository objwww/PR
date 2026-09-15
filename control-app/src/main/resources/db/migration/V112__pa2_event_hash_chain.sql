-- PA-A2（Phase A 第二片）：rca_event 事件哈希链——"tamper-evident under the assumed
-- DB write boundary"（评审 R9 口径：同库管理员可整链重算，本链提供库内写边界的
-- 篡改可证性；真正的防重算增强=Merkle checkpoint 签名写外部 WORM，另行增量）。
--
-- 设计基线：docs/告警-Agent授权-方案Bv2严格执行-全量纳入设计-v2.md §2.12/§4 L8
-- 链式：event_hash = sha256(prev_hash ":" seq ":" event_type ":" payload_digest)；
-- 首行 prev_hash = 'GENESIS'；legacy 行（本迁移前落账）双列为 NULL——验链器将
-- NULL 行视为链段边界跳过校验，新事件以 NULL 尾为 GENESIS 起新验链段。
--
-- 授权说明：rca_event 写方 control_app（V14 已授），无新授权。

alter table rca_event add column prev_hash varchar(64);
alter table rca_event add column event_hash varchar(64);

comment on column rca_event.prev_hash is
    'PA-A2：前一条事件哈希（同 run seq-1；首行/legacy 尾后首行 = ''GENESIS''）';
comment on column rca_event.event_hash is
    'PA-A2：sha256(prev_hash:seq:event_type:payload_digest)——同事务随 append 写入，'
    '验链作业每日重算比对（tamper-evident under the assumed DB write boundary）';
