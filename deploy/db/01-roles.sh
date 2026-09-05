#!/bin/bash
# ============================================================================
# 数据库角色创建（幂等），postgres 容器 docker-entrypoint-initdb.d 阶段执行。
# 密码来自容器环境变量 CONTROL_DB_PASSWORD / PUBLISHER_DB_PASSWORD /
# ARENA_DB_PASSWORD / CHAOS_ADMIN_DB_PASSWORD / EVAL_DB_PASSWORD（AM2 M2-03）。
# 授权不在这里做——本脚本执行时表尚不存在；授权见各域迁移：
#   control 域 V2__grants.sql；arena 域 order-arena/db/migration V1~V5。
# 注意：docker-entrypoint-initdb.d 只在数据卷首次初始化时执行；存量库（如 195）
# 的角色由 arena 域 V1 迁移内的幂等 DO 块补齐（两者语义一致）。
# 测试环境（Testcontainers）由测试基建以超级用户执行等价 DO 块。
# ============================================================================
set -euo pipefail

: "${CONTROL_DB_PASSWORD:?CONTROL_DB_PASSWORD required}"
: "${PUBLISHER_DB_PASSWORD:?PUBLISHER_DB_PASSWORD required}"
: "${ARENA_DB_PASSWORD:?ARENA_DB_PASSWORD required}"
: "${CHAOS_ADMIN_DB_PASSWORD:?CHAOS_ADMIN_DB_PASSWORD required}"
: "${EVAL_DB_PASSWORD:?EVAL_DB_PASSWORD required}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
do \$\$
begin
    if not exists (select from pg_roles where rolname = 'control_app') then
        create role control_app login password '${CONTROL_DB_PASSWORD}';
    else
        alter role control_app with login password '${CONTROL_DB_PASSWORD}';
    end if;
    if not exists (select from pg_roles where rolname = 'publisher_app') then
        create role publisher_app login password '${PUBLISHER_DB_PASSWORD}';
    else
        alter role publisher_app with login password '${PUBLISHER_DB_PASSWORD}';
    end if;
    -- AM2 M2-03（C-3 角色拆分）：
    --   arena_app        业务靶场进程：arena 业务表 RW + chaos_session 只读 + GT 禁读
    --   chaos_admin_app  chaos 管理面（独立容器/eval-mgmt）：session/event/GT/scenario_map 写
    --   eval_app         评测侧只读（AM3 eval-runner 前身）：GT/业务事实/映射 可读 + 映射回填列
    if not exists (select from pg_roles where rolname = 'arena_app') then
        create role arena_app login password '${ARENA_DB_PASSWORD}';
    else
        alter role arena_app with login password '${ARENA_DB_PASSWORD}';
    end if;
    if not exists (select from pg_roles where rolname = 'chaos_admin_app') then
        create role chaos_admin_app login password '${CHAOS_ADMIN_DB_PASSWORD}';
    else
        alter role chaos_admin_app with login password '${CHAOS_ADMIN_DB_PASSWORD}';
    end if;
    if not exists (select from pg_roles where rolname = 'eval_app') then
        create role eval_app login password '${EVAL_DB_PASSWORD}';
    else
        alter role eval_app with login password '${EVAL_DB_PASSWORD}';
    end if;
end
\$\$;

-- Flyway V1（建表）由 control_app 角色执行（owner）；PG15+ 的 public schema
-- 默认不收 PUBLIC 的 CREATE，须显式授予（compose 实部署暴露，IT 以超级用户跑未覆盖）
grant create on schema public to control_app;
SQL
