-- ============================================================================
-- 数据库角色与权限（架构冻结文档 v2 第七章；v2.2 §1）
-- 在 postgres 容器 docker-entrypoint-initdb.d 阶段以超级用户执行（幂等）。
-- 密码来自容器环境变量 CONTROL_DB_PASSWORD / PUBLISHER_DB_PASSWORD。
--
-- 权限冻结矩阵（M0）：
--   control_app    : outbox_command 仅 SELECT/INSERT（UPDATE/DELETE 物理拒绝，AFT-06）
--   publisher_app  : outbox_command 仅 SELECT/UPDATE（不能伪造写意图）
--   publication_resource: Publisher 写，Control 只读（v2.2 §1 规则 2）
--   不可变表（pr_revision / execution_event）: 任何应用角色无 UPDATE/DELETE，
--     另有 trigger 兜底（V1__m0_schema.sql 末尾）。
-- ============================================================================

\setcontrol `echo "$CONTROL_DB_PASSWORD"`
\setpublish `echo "$PUBLISHER_DB_PASSWORD"`

do $$
begin
    if not exists (select from pg_roles where rolename = 'control_app') then
        create role control_app login password :'control';
    else
        alter role control_app with login password :'control';
    end if;
    if not exists (select from pg_roles where rolename = 'publisher_app') then
        create role publisher_app login password :'publish';
    else
        alter role publisher_app with login password :'publish';
    end if;
end
$$;

-- ---------- schema 级 ----------
grant usage on schema public to control_app, publisher_app;

-- ---------- control_app ----------
grant select, insert, update on pr_subject to control_app;
grant select, insert on pr_revision to control_app;            -- 不可变：无 update/delete
grant select, insert, update on review_run to control_app;
grant select, insert, update on run_step to control_app;
grant select, insert, update on work_item to control_app;
grant select, insert, update on step_attempt to control_app;   -- status 推进/记 STALE
grant select, insert on execution_event to control_app;        -- 只追加
grant select, insert, update on review_finding to control_app; -- PENDING→PUBLISHED/SUPERSEDED
grant select, insert on artifact to control_app;               -- 登记表
grant select, insert on outbox_command to control_app;         -- 无 update/delete（AFT-06）
grant select, insert on outbox_dependency to control_app;
grant select on publication_resource to control_app;           -- 漂移只读视角

revoke update, delete on outbox_command from control_app;      -- 显式冻结，防未来 grant all 漂移

-- ---------- publisher_app ----------
grant select on pr_subject, pr_revision, review_run, run_step,
    work_item, step_attempt, review_finding, artifact,
    outbox_dependency to publisher_app;
grant select, update on outbox_command to publisher_app;       -- 不能 insert/delete
grant select, insert on execution_event to publisher_app;      -- Publication 事件
grant select, insert, update on publication_resource to publisher_app;

revoke insert, delete on outbox_command from publisher_app;    -- 显式冻结
