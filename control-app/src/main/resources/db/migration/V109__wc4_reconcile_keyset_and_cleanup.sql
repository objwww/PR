-- WC-4（方案 v2 §6.1/§5.3，docs/告警-看门狗与取消传播-代码复核及修复技术方案-v2.md）：
-- 对账扫描公平性 + 终态 Run 清理通道的索引面。
--
-- §6.1 扫描饥饿：findActiveForReconcile 只取最老 N 条（无游标）时，活跃 Run 超过
-- 批上限即出现"新记录永不被检查"。改 keyset 分页 findActiveForReconcileAfter
-- (created_at,id) 后，排序键需要 (created_at,id) 二列——重建 V108 的单列部分索引
-- （谓词不变：与 isActive()/V12 uq 索引同集）。
--
-- §5.3 终态 Run 子任务清理：独立游标 + 独立索引，不能让清理批次与活跃对账批次
-- 争用排序面。驱动面 = 非终态任务集（有界：只含在飞/排队/退避任务），按
-- (created_at,id) keyset 走，join rca_run 判终态——不被全量历史终态 Run 拖慢。

drop index if exists ix_rca_run_active_reconcile;

create index ix_rca_run_active_reconcile
    on rca_run (created_at, id)
    where state in ('QUEUED', 'RUNNING', 'REPORTING');

create index ix_rca_task_open_cleanup
    on rca_task (created_at, id)
    where state in ('READY', 'BLOCKED', 'RETRY_WAIT', 'LEASED', 'RUNNING');
