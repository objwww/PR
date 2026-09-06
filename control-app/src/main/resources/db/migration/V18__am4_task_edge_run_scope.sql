-- ============================================================================
-- V18 —— AM4 任务 DAG 边同 run 归属约束（M4-04；docs/告警AM4-落码技术方案.md v1.2）
--
--   V8 的 rca_task_edge 只保证 from/to 是真实任务（FK→rca_task.id），但边的 run_id
--   可以谎报归属——跨 run 连边在 DB 层可行，推进器按 run 载边时会把别家 run 的
--   任务卷进本 run 的 DAG。本迁移补强（复用不重建表，技术方案 v1.1 修正①）：
--   组合外键强制 edge.run_id == from/to 任务各自所属的 run。
--
--   迁移编号说明：P-45 冻结表 V12~V17 各归其主（V12 状态扩容/V13 预算/V14 rca_event/
--   V15 工具账本/V16 Evidence/V17 Claim），M4-04 的 DDL 按"一迁移一任务"顺延为 V18。
--   注：生产路径从不 DELETE rca_task 行（子表引用侧不加支撑索引，父行删除仅存在于
--   测试 TRUNCATE CASCADE）。
-- ============================================================================

-- FK 目标：rca_task 的 (id, run_id) 配对唯一（id 已是 PK，约束零冗余成本）
alter table rca_task add constraint uq_rca_task_id_run unique (id, run_id);

-- 边的 run_id 必须与 from/to 任务各自所属 run 一致——跨 run 连边在 DB 层不可能
alter table rca_task_edge
    add constraint fk_rca_task_edge_from_in_run
        foreign key (from_task_id, run_id) references rca_task (id, run_id),
    add constraint fk_rca_task_edge_to_in_run
        foreign key (to_task_id, run_id) references rca_task (id, run_id);
