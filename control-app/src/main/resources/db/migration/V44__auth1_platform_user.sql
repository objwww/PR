-- ============================================================================
-- V44 —— AUTH-1 平台账号表（登录白名单独立载体）
--   设计定案（澄清后）：登录权限是独立的平台账号概念，不挂值班花名册——
--   有账号者皆可登录（含不值班的人）；duty_member 回归纯排班语义。
--   env 预置 operator 保留为引导管理员（例外，不在本表种用户）。
--
-- 列约定：
--   username      登录名（主键，text——与 duty_member.name 同风）；
--   password_hash BCrypt（not null——账号即密码账号，无密码即无账号）；
--   role          授权角色（默认 OPERATOR，应用层铸 ROLE_+role）；
--   active        停用即无法新登录（已发会话不强制踢出，v1 限制）。
--
-- 密钥纪律：password_hash 只经写端点入库，任何读接口不出（应用层 SELECT
--   列清单保证；本表无任何读视图消费方）。
--
-- 授权（V43 同构惯例）：control_app 增改查（无 DELETE——账号只停用不物理删）；
--   其余角色显式冻结。
--
-- 回滚（一语句）：drop table platform_user;
-- ============================================================================

create table platform_user (
    username      text primary key,
    display_name  text not null default '',
    password_hash text not null,
    role          text not null default 'OPERATOR',
    active        boolean not null default true,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

grant select, insert, update on platform_user to control_app;

-- 显式冻结（V2/V9/V43 惯例）：PUBLIC 与其他应用角色零权限
revoke all on platform_user from public;
revoke all on platform_user from publisher_app;
do $$
begin
    if exists (select from pg_roles where rolname = 'arena_app') then
        revoke all on platform_user from arena_app;
    end if;
    if exists (select from pg_roles where rolname = 'chaos_admin_app') then
        revoke all on platform_user from chaos_admin_app;
    end if;
    if exists (select from pg_roles where rolname = 'notify_app') then
        revoke all on platform_user from notify_app;
    end if;
end
$$;
