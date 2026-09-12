-- ============================================================================
-- V94 —— UX-03 处置单↔incident 关联列（方案 §三.2 告警列表「负责人」列前置）
--   operator_case.incident_id uuid NULL——来源引用列（V26 run_id 同律：可空、
--   无 FK——ops 域不硬绑 alert 域 incident 生命周期，归档/保留期删除不受阻）。
--   写入面 = PostgresOperatorCaseRepository.INSERT_SQL：创建时由 run_id 经
--   rca_run.incident_id 解析落列（run_id 可空的预算/通知来源如实 NULL）；
--   UPDATE_SQL 来源列零开口不变（快照冻结同律）。
--   只对新单生效：历史 case→incident 关联无据可查，不回填（诚实 null）。
--
-- 编号说明：V89=EN 已上 195；V90~V93 R 线在飞占用，本迁移取 V94。
--
-- 唯一性裁定（不加「一 incident 至多一 open case」部分唯一约束）：
--   case 幂等身份是 (tenant,fingerprint)（V26 uq）；incident 是 incident_key
--   粒度（不含级别/episode），同 incident 跨 episode / 多 fingerprint 合法
--   存在多单。读面（告警列表 owner 列）按 created_at 最新确定性取一。
-- 授权：V26 表级 grant 覆盖新列，无新增授权面。
-- ============================================================================

alter table operator_case add column incident_id uuid;

comment on column operator_case.incident_id is
    'UX-03 来源 incident 关联（V26 run_id 同律：可空、无 FK；创建时由 run_id 经 rca_run 解析落列，历史行不回填）';

-- 读面 = 告警列表 owner 列的 open case 反查（OPEN/ACKED 部分索引，
-- ix_operator_case_status 同律；RESOLVED 不进列表负责人面）
create index ix_operator_case_incident_open on operator_case(incident_id)
    where status in ('OPEN','ACKED');
