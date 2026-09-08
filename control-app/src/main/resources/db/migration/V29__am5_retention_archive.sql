-- ============================================================================
-- V29 —— AM5 M5-18：保留域三表（retention_policy / legal_hold / archive_manifest）
--
--   retention_policy：版本化 insert-only（最新 policy_version = 生效策略）——
--     无 update/delete 授权开口（INV-AM5-1 同构：历史不可覆盖）。
--   legal_hold：released_at null = 生效中（INV-AM5-9：阻断一切清理）；唯一可变面
--     = released_at 单列开口（release 动作），reason/created_by 审计列不可改。
--   archive_manifest：归档证据面（导出条数/digest 校验；M5-19 ArchiveService 生产/
--     推进）；state 单向 EXPORTED→VERIFIED→ARCHIVED；uq(partition_name) =
--     分区归档恰一次栅栏（双归档 = 双删风险，DB 面 close）。
-- ============================================================================

create table retention_policy (
    id                 uuid primary key,
    policy_version     integer not null,
    hot_retention_days bigint not null,
    cold_location      text not null,
    legal_hold         boolean not null default false,
    created_at         timestamptz not null default now(),
    constraint ck_retention_policy_version check (policy_version >= 1)
);

comment on table retention_policy is
    'AM5 M5-18 保留策略（insert-only 版本链，最新版本生效；hot_retention 以整天数计）';

create table legal_hold (
    id          uuid primary key,
    scope       text not null,
    reason      text not null,
    created_by  varchar(64) not null,
    created_at  timestamptz not null default now(),
    released_at timestamptz
);

comment on table legal_hold is
    'AM5 M5-18 legal hold（released_at null = 生效中；scope=表名族域或 表名:分区名 分区域）';

create table archive_manifest (
    id             uuid primary key,
    partition_name text not null,
    row_count      bigint not null,
    content_digest char(64) not null,
    export_ref     text not null,
    state          varchar(16) not null default 'EXPORTED',
    created_at     timestamptz not null default now(),
    constraint ck_archive_manifest_state
        check (state in ('EXPORTED', 'VERIFIED', 'ARCHIVED')),
    constraint uq_archive_manifest_partition unique (partition_name)
);

comment on table archive_manifest is
    'AM5 M5-18/19 归档 manifest（partition/row_count/digest/export_ref/state；导出校验证据面）';

-- 授权面：策略链 insert-only；hold 单列 release；manifest 单列 state 推进
grant select, insert on retention_policy to control_app;
revoke update, delete on retention_policy from control_app;

grant select, insert on legal_hold to control_app;
grant update (released_at) on legal_hold to control_app;
revoke delete on legal_hold from control_app;

grant select, insert on archive_manifest to control_app;
grant update (state) on archive_manifest to control_app;
revoke delete on archive_manifest from control_app;

-- ---------- 4. 分区摘离收口函数（BA-42③，195 真 PG 实证） ----------
--
--   DETACH PARTITION = ALTER TABLE，需表主权限；control_app 按最小授权原则无主
--   身份，归档工序（M5-19）直连即 permission denied → FAILED_DETACH 单向卡死。
--   提权收口 = security definer 函数：动作面钉死「仅 rca_event 族的分区摘离」
--   单一动作；分区名白名单同应用面（SAFE_IDENTIFIER）；分区不存在/已摘离即异常
--   （与网关前置检查同语义，fail-closed）。
create function pr_archive_detach_partition(p_partition text)
returns void
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    v_parent text;
begin
    if p_partition !~ '^[a-z_][a-z0-9_]*$' then
        raise exception '非法分区标识符: %', p_partition;
    end if;
    select p.relname into v_parent
      from pg_catalog.pg_inherits i
      join pg_catalog.pg_class c on c.oid = i.inhrelid
      join pg_catalog.pg_class p on p.oid = i.inhparent
     where c.relname = p_partition
       and p.relname = 'rca_event';
    if v_parent is null then
        raise exception '分区不存在或已摘离: %', p_partition;
    end if;
    execute format('alter table %I detach partition %I', v_parent, p_partition);
end
$$;

revoke all on function pr_archive_detach_partition(text) from public;
grant execute on function pr_archive_detach_partition(text) to control_app;

comment on function pr_archive_detach_partition(text) is
    'AM5 M5-19 分区归档摘离收口（security definer：control_app 无表主权限，提权面=仅 rca_event 族 DETACH 单动作；BA-42③）';
