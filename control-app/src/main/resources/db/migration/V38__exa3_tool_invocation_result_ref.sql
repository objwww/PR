-- V38 — EX-A3 F08/F09 可恢复驱动 checkpoint 增列（docs/告警-EXA3-可恢复驱动.md §1）
--
--   P1-03 checkpoint 最少字段中既有列已覆盖 run/task/attempt/action_seq/
--   request_digest/dispatch 状态（V15/V36 既有列，映射见契约文档）；本迁移只补
--   缺失的 result_ref。reservation_id 可推导恒等 = ReservationKey(run_id,
--   task_id, attempt_id, call_seq, TOOL_CALL)（PostgresRunBudgetLedger 同键持久），
--   driver_epoch 经 attempt_id join rca_attempt.lease_epoch——均不设冗余列。
--
--   回滚：alter table rca_tool_invocation drop constraint fk_rca_tool_invocation_result_ref;
--         alter table rca_tool_invocation drop column result_ref;
--   编号纪律：rebase 时以下一可用号为准替换 V38。

alter table rca_tool_invocation add column result_ref uuid;

comment on column rca_tool_invocation.result_ref is
    'EX-A3 F09 恢复 checkpoint：结果引用（rca_evidence.id），SUCCESS 前随 PENDING CAS 落值；阶段③恢复从本列幂等收尾不触网';

-- 引用完整性 DB 面（BA-60 同律：幽灵引用在约束面被拒，不靠 Java 侧自觉）；
-- 既有行 result_ref 全 NULL，校验恒过
alter table rca_tool_invocation
    add constraint fk_rca_tool_invocation_result_ref
    foreign key (result_ref) references rca_evidence (id);

create index if not exists idx_rca_tool_invocation_run_task
    on rca_tool_invocation (run_id, task_id);
