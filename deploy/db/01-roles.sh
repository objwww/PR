#!/bin/bash
# ============================================================================
# 数据库角色创建（幂等），postgres 容器 docker-entrypoint-initdb.d 阶段执行。
# 密码来自容器环境变量 CONTROL_DB_PASSWORD / PUBLISHER_DB_PASSWORD。
# 授权不在这里做——本脚本执行时表尚不存在；授权见 Flyway V2__grants.sql。
# 测试环境（Testcontainers）由测试基建以超级用户执行等价 DO 块。
# ============================================================================
set -euo pipefail

: "${CONTROL_DB_PASSWORD:?CONTROL_DB_PASSWORD required}"
: "${PUBLISHER_DB_PASSWORD:?PUBLISHER_DB_PASSWORD required}"

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
end
\$\$;

-- Flyway V1（建表）由 control_app 角色执行（owner）；PG15+ 的 public schema
-- 默认不收 PUBLIC 的 CREATE，须显式授予（compose 实部署暴露，IT 以超级用户跑未覆盖）
grant create on schema public to control_app;
SQL
