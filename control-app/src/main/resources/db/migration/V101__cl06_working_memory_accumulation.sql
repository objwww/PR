-- ============================================================================
-- V101 —— CL-06 累计记忆结构升级（告警-Agent闭环修复 v1 §5.1）
--
-- rca_working_memory（V91）追加两列，不重建表、不改既有行：
--   * schema_version：1 = 旧协议（checkpoint_revision 存 decision_seq 语义）；
--     2 = 新协议（checkpoint_revision 严格对应 rca_primary_checkpoint.revision，
--     CL-01 围栏启用后）。存量行保留旧 schema 不回填——历史 decisionSeq 不冒充
--     新 revision（§5.1"不能把历史 decisionSeq 冒充新 revision"）。
--   * parent_memory_id：不可变父引用（§5.2 从 checkpoint.memory_id 精确读上一版
--     的累计链锚——按稳定内容去重合并，反证跨轮保留）。
-- 新旧唯一键 (run_id, task_id, checkpoint_revision) 冲突面：首期按 §5.1"新 Run
-- 启用新协议"——升级后铸造的 Run 全程 schema 2；升级中途的存量 Run 走完旧协议。
-- 授权不变（control_app select,insert）。回滚：两列均可空/有默认，向前兼容。
-- 号段：V100 已占（CL-05 Skill 绑定），本卡按下一可用号 V101 落位。
-- ============================================================================

alter table rca_working_memory
    add column schema_version   int not null default 1,
    add column parent_memory_id uuid;

comment on column rca_working_memory.schema_version is
    '记忆协议版本：1=旧协议（revision 列存 decision_seq），2=新协议（真 checkpoint revision，CL-06）';
comment on column rca_working_memory.parent_memory_id is
    '累计链父引用（上一版记忆 id，来自 checkpoint.memory_id 精确指针；null=首版）';
