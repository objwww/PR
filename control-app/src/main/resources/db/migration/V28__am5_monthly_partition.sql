-- ============================================================================
-- V28 —— AM5 M5-18：rca_event 转 PG 声明式月 RANGE 分区（created_at 分区键）
--
--   策略裁定（C-23②，落码方案 §M5-18②"新分区表+双写切换或离线回填"的执行面）：
--     新分区表 + 存量复制 + 同名换身（单事务）。开发期体量小复制瞬时完成；
--     真产体量的离线回填/双写切换属部署段工序，不在迁移内（P-55 设计评审遗留）。
--
--   E-17 坑（§M5-18② 原文）：分区表唯一约束必须含分区键——
--     uq_rca_event_run_seq    (run_id, seq)          → (run_id, seq, created_at)
--     uq_rca_event_run_event_id (run_id, event_id)    → (run_id, event_id, created_at)
--     pk_rca_event            (id)                   → (id, created_at)
--     (run_id, seq) 的无洞单调由 M4-10 计数器行锁保证（应用面不变量），
--     约束放宽只为满足 PG 分区规则，不放松防重入语义。
--
--   视图 rca_agent_event 按 oid 绑定旧表——换身前 drop、换身后重建（授权随建）。
--   default 分区兜底：边界外写入永不因缺分区失败（归档候选判定由 RetentionService
--   排除 default，见 ops/application/RetentionService）。
--
--   C-23③（P-55 裁定）：alert_inbox / rca_evidence 本期**不转分区**——二者被
--   alert_event.inbox_id / rca_snapshot_member.evidence_id 单列 FK 引用，分区表
--   唯一约束必须含分区键 ⇒ FK 须扩为复合键 = 动 AM1/AM4 热表写路径（evidence
--   完整性链），超 M5-18 单任务窗；扩键设计列入开放项（见 告警-PROGRESS C-23③）。
--   保留域（V29 retention_policy/legal_hold/archive_manifest）按表族契约覆盖三表。
-- ============================================================================

create table rca_event_partitioned (
    like rca_event including defaults including generated including storage including comments
) partition by range (created_at);

alter table rca_event_partitioned
    add constraint pk_rca_event primary key (id, created_at),
    add constraint uq_rca_event_run_seq unique (run_id, seq, created_at),
    add constraint uq_rca_event_run_event_id unique (run_id, event_id, created_at),
    add constraint ck_rca_event_seq_positive check (seq >= 1),
    add constraint fk_rca_event_run foreign key (run_id) references rca_run (id);

create index ix_rca_event_run_created on rca_event_partitioned (run_id, created_at);

-- 初始分区：当前月 + 次月 + default 兜底（后续月由归档工序前滚创建，M5-19）
create table rca_event_2026_09 partition of rca_event_partitioned
    for values from ('2026-09-01 00:00:00+00') to ('2026-10-01 00:00:00+00');
create table rca_event_2026_10 partition of rca_event_partitioned
    for values from ('2026-10-01 00:00:00+00') to ('2026-11-01 00:00:00+00');
create table rca_event_default partition of rca_event_partitioned default;

-- 存量搬迁（开发期体量小；真产离线回填/双写切换属部署段工序，C-23②）
insert into rca_event_partitioned select * from rca_event;

-- 同名换身（视图按 oid 绑定旧表，先拆后建）
drop view rca_agent_event;
drop table rca_event;
alter table rca_event_partitioned rename to rca_event;

create view rca_agent_event as
    select run_id, seq, event_id, event_type, payload, payload_digest, created_at
      from rca_event;

comment on table rca_event is
    'AM4 M4-10 统一事件账本（AM5 M5-18 月 RANGE 分区版：唯一约束并入分区键 created_at）';
comment on view rca_agent_event is
    'AM4 M4-12 rca_event 只读兼容投影（M5-18 分区换身后重建；应用角色无写授权）';

-- 换身不继承授权（LIKE 不拷特权）：按 V14 授权面显式重授
grant select, insert on rca_event to control_app;
grant select on rca_agent_event to control_app;
grant select on rca_event_2026_09 to control_app;
grant select on rca_event_2026_10 to control_app;
grant select on rca_event_default to control_app;
