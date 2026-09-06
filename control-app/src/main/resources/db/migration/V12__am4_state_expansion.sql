-- ============================================================================
-- V12 —— AM4 状态全集扩容（M4-02；docs/告警AM4-落码技术方案.md v1.2）
--
--   additive only：AM1 旧值全部保留、语义冻结、不改名（评审 v1.1 修正④）。
--   Java 侧双读契约 RcaStateContract 已先行（M4-01），本迁移同步 DB CHECK——
--   此前 DB 不可能给出 AM4 新值（旧 CHECK 拒绝），迁移后新旧状态同时可写入。
--
--   rca_task  6→11 态：+BLOCKED/RUNNING/SKIPPED/FAILED_TERMINAL/STALE
--   rca_run   6→9  态：+REPORTING/PARTIAL/EXPIRED
--
--   谓词同步（活跃集 +REPORTING）：REPORTING = 全部任务了断后的报告/裁决组装中，
--   仍属活跃——同一 incident 在组装期不得开新 run（与 RcaRunState.isActive() 一致）。
-- ============================================================================

-- ---------- rca_task 状态扩容（V7 ck_rca_task_state 原位扩集） ----------

alter table rca_task drop constraint ck_rca_task_state;
alter table rca_task add constraint ck_rca_task_state
    check (state in ('READY','LEASED','RETRY_WAIT','DONE','CANCELLED','DEAD',
                     'BLOCKED','RUNNING','SKIPPED','FAILED_TERMINAL','STALE'));

-- ---------- rca_run 状态扩容（V7 ck_rca_run_state 原位扩集） ----------

alter table rca_run drop constraint ck_rca_run_state;
alter table rca_run add constraint ck_rca_run_state
    check (state in ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED','SUPERSEDED',
                     'REPORTING','PARTIAL','EXPIRED'));

-- ---------- 收尾不变式：活跃集 +REPORTING（V7 ck_rca_run_finish 原位扩集） ----------

alter table rca_run drop constraint ck_rca_run_finish;
alter table rca_run add constraint ck_rca_run_finish
    check (state in ('QUEUED','RUNNING','REPORTING') or finished_at is not null);

-- ---------- 唯一活跃约束谓词 +REPORTING（INV-AM1-2 延续，M4-01 isActive 同源） ----------

drop index uq_rca_run_active_incident;
create unique index uq_rca_run_active_incident
    on rca_run(incident_id) where state in ('QUEUED','RUNNING','REPORTING');
