-- PA-A1（Phase A 第一片·meaningful progress）：rca_attempt 进度双列 + LIVE_BUT_STUCK 完成种
--
-- 设计基线：docs/告警-Agent授权-方案Bv2严格执行-全量纳入设计-v2.md §3.2（Progress 三字段）
--   heartbeat_at                = Worker 活着（rca_task.lease_until，已有，不动）
--   last_activity_at            = 系统在干活（worker 心跳回写；LLM token 流只算 activity）
--   last_meaningful_progress_at = incident 真推进（检查点 APPLIED 提交同事务回写；
--                                 lease renewal 不是 progress——评审裁定纪律）
--
-- 语义分界（评审 R5/R13）：LIVE_BUT_STUCK（心跳活 + 无有效进展）归控制面 RunReconciler
--   新档；重复模式归 run 内 DoomLoopGuard；绝对上限归 RunBudgetGate——三层互补不替换。
--   只读域（R2/R3 仍 VALIDATE_ONLY）取消前无需 reconcile；AM8 解锁 mutation 后本路径
--   必须先过 operation 状态闸（设计 §3.3：有 DISPATCHED/UNKNOWN 一律先 reconcile）。
--
-- 授权说明：rca_attempt 写方 control_app（V7 已授表级 update），无新授权。

alter table rca_attempt add column last_activity_at timestamptz;
alter table rca_attempt add column last_meaningful_progress_at timestamptz;

comment on column rca_attempt.last_activity_at is
    'PA-A1：系统活跃时刻（worker 心跳回写；LLM token 流/工具等待只算 activity）。'
    '判 LIVE_BUT_STUCK 时与 last_meaningful_progress_at 分离——持续吐 token 不构成进展';
comment on column rca_attempt.last_meaningful_progress_at is
    'PA-A1：有效进展时刻（检查点 APPLIED 提交同事务回写；新证据/里程碑/决策推进）。'
    'NULL = 该 attempt 尚无进展写点（判定回退 startedAt 基线）；lease 续租永不回写本列';

-- LIVE_BUT_STUCK 完成种（RunReconciler 新档终止：心跳活但无有效进展）——约束放宽
alter table rca_run drop constraint ck_rca_run_completion_kind;
alter table rca_run add constraint ck_rca_run_completion_kind
    check (completion_kind is null
        or completion_kind in ('SHADOW_EVIDENCE_ONLY', 'QUEUE_DEADLINE', 'DEADLINE_EXPIRED',
                               'LIVE_BUT_STUCK'));
