-- ============================================================================
-- V24 —— AM5 M5-09 ConfigBundle 发布/回滚（release 域）
--   config_bundle        不可变配置束（只授 select,insert——immutable 的 DB 面；
--                        INV-AM5-5：密钥不入 bundle，DB 面锁形状，密钥检测归发布面）
--   config_bundle_active 单行 active pointer（激活/回滚 = 该行 CAS update，
--                        无半激活态；历史 bundle 行永不改）
--
-- 编号说明：落码方案 §M5-09① 指名 V24__am5_config_bundle.sql（O-1 的扩展列窗口
-- 已随 V23 发布关闭，见 PROGRESS M5-08 台账）。
-- ============================================================================

-- ---------- 1. config_bundle：不可变配置束 ----------

create table config_bundle (
    id             uuid primary key,
    bundle_digest  char(64) not null,
    revision       bigint not null check (revision > 0),
    content        jsonb not null check (jsonb_typeof(content) = 'object'),
    created_by     text not null,
    created_at     timestamptz not null,
    constraint uq_config_bundle_digest unique (bundle_digest)
);

comment on table config_bundle is
    'AM5 ConfigBundle 不可变配置束（M5-09；INV-AM5-5 密钥不入 bundle——发布面键名扫描 fail-closed，DB 面只锁对象形状）';
comment on column config_bundle.bundle_digest is
    'canonical content 的 sha256（internal-v1 规范化 JSON，键序无关）——发布幂等锚 + 重跑一致锚';
comment on column config_bundle.content is
    'prompt/规则/工具策略/模型路由/阈值/policy_version 全量内容（policy_version 必带，v1.1 裁定）';

-- ---------- 2. config_bundle_active：单行 active pointer ----------

create table config_bundle_active (
    id            smallint primary key check (id = 1),
    bundle_digest char(64) references config_bundle (bundle_digest),
    activated_at  timestamptz,
    activated_by  text,
    constraint ck_config_bundle_active_unactivated
        check (bundle_digest is not null or (activated_at is null and activated_by is null))
);

-- 单行 pointer 种子行（未激活态：digest/activated_at/activated_by 全 null）
insert into config_bundle_active (id) values (1);

comment on table config_bundle_active is
    'AM5 active pointer 单行表（id=1 唯一行）：激活/回滚 = CAS update（WHERE bundle_digest IS NOT DISTINCT FROM 期望旧值），无半激活态';

-- ---------- 3. 授权（V7 惯例：immutable 面 + 显式冻结） ----------

grant select, insert on config_bundle to control_app;
revoke update, delete on config_bundle from control_app;

grant select, update on config_bundle_active to control_app;
revoke delete on config_bundle_active from control_app;

revoke all on config_bundle, config_bundle_active
    from publisher_app, notify_app, eval_app, public;
