-- ============================================================================
-- V14 —— AM4 统一事件账本 rca_event（M4-10/12；技术方案 v1.3 §6 rca_event 条目）
--
--   评审裁定：不引入 global seq。每 run 分段原子序号：
--     rca_run.last_event_seq 与事件插入同一短事务（本迁移补列）——
--     run 行行锁（UPDATE ... RETURNING last_event_seq）串行化同 run 并发追加，
--     状态事务回滚 → 事件与 seq 一并回滚，无空洞、无倒退。
--   幂等/冲突语义：UNIQUE(run_id, event_id)——同 event_id + 同 digest 重放 = 幂等
--   （返回既有 seq）；同 event_id 但 digest 不同 = 冲突显式报错（禁静默 no-op）。
--   append-only：control_app 仅授 select/insert（无 update/delete）。
--   M4-12：rca_agent_event 只读兼容视图投影权威表——应用角色无写授权（写入被拒），
--   双写不存在；事件唯一入口 = rca_event（EventAppender）。
-- ============================================================================

alter table rca_run add column last_event_seq bigint not null default 0;

create table rca_event (
    id             uuid        not null,
    run_id         uuid        not null,
    seq            bigint      not null,
    event_id       uuid        not null,
    event_type     varchar(64) not null,
    payload        jsonb       not null,
    payload_digest varchar(64) not null,
    created_at     timestamptz not null default now(),
    constraint pk_rca_event primary key (id),
    constraint fk_rca_event_run foreign key (run_id) references rca_run (id),
    constraint uq_rca_event_run_seq unique (run_id, seq),
    constraint uq_rca_event_run_event_id unique (run_id, event_id),
    constraint ck_rca_event_seq_positive check (seq >= 1)
);

create index ix_rca_event_run_created on rca_event (run_id, created_at);

comment on table rca_event is
    'AM4 M4-10 统一 append-only 事件账本（每 run 原子分段 seq；event_id+digest 幂等/冲突显式）';

-- 只读兼容视图（M4-12）：旧消费面名字续命；应用角色仅 SELECT——写入被拒即无双写
create view rca_agent_event as
    select run_id, seq, event_id, event_type, payload, payload_digest, created_at
      from rca_event;

comment on view rca_agent_event is
    'AM4 M4-12 rca_event 只读兼容投影（应用角色无写授权；append 唯一入口 = rca_event）';

grant select, insert on rca_event to control_app;
grant select on rca_agent_event to control_app;
