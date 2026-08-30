-- ============================================================================
-- V2 授权（架构冻结文档 v2 第七章；v2.2 §1）
-- 由 Flyway 以表 owner 身份执行，须在 V1 之后、应用启动之前。
-- 前置：角色 control_app / publisher_app 已由 deploy/db/01-roles.sh（或测试
-- 基建的等价 DO 块）创建。
--
-- 权限冻结矩阵（M0）：
--   control_app    : outbox_command 仅 SELECT/INSERT（UPDATE/DELETE 物理拒绝，AFT-06）
--   publisher_app  : outbox_command 仅 SELECT/UPDATE（不能伪造写意图）
--   publication_resource: Publisher 写，Control 只读（v2.2 §1 规则 2）
--   不可变表（pr_revision / execution_event）: 应用角色无 UPDATE/DELETE，
--     另有 trigger 兜底（V1 末尾）。
-- ============================================================================

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
-- pr_subject 拆出单列：评审修正 #5 要求 publisher 在 T3 事务内推进
-- last_resolved_sequence 游标（读用 FOR UPDATE 行锁）。T17 集成测试发现
-- 仅 SELECT 授权使该路径在真库上物理不可行（permission denied）——列级
-- UPDATE 是最小授权：FOR UPDATE 行锁可用（PG 只要求任一列 UPDATE 权），
-- 且 publisher 仍无法篡改 epoch/序号/current_revision_id 等其余列。
grant select on pr_subject to publisher_app;
grant update (last_resolved_sequence, updated_at) on pr_subject to publisher_app;
grant select on pr_revision, review_run, run_step,
    work_item, step_attempt, review_finding, artifact,
    outbox_dependency to publisher_app;
grant select, update on outbox_command to publisher_app;       -- 不能 insert/delete
grant select, insert on execution_event to publisher_app;      -- Publication 事件
grant select, insert, update on publication_resource to publisher_app;

revoke insert, delete on outbox_command from publisher_app;    -- 显式冻结
