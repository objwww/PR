-- PC-C3（Phase C 第三片/收官）：durable suspension 台账 + 双时钟 deadline 面。
--
-- 设计基线 §2.10（R15）：
--   * 发起审批 → 落检查点 → 释放 scheduler_slot → SUSPENDED_ON_APPROVAL →
--     回调后补铸 attempt → 恢复（复用 Redrive 语义）；
--   * 双时钟：incident_wall_deadline 永不冻结（真实用户等待的诚实时钟）——
--     对账豁免面（RunReconciler 16 参形态）保证挂起 run 不被 AUTO_EXPIRE/
--     LIVE_BUT_STUCK 误杀；system_active_budget 挂起期冻结（slot 已释放=不产算力）；
--     approval_expires_at 独立 300s（V119）；
--   * human_wait_time 单列——MMTM 不再把人的等待算进系统耗时。
--
-- Worker 检查点/补铸 attempt 的全量接线随 Phase D（首个真实 R2 解锁时挂起成为
-- 承重面）；本波落台账 + 豁免 + 观测（shadow 语义完整）。

create table approval_suspension (
    suspension_id   uuid primary key,
    run_id          uuid not null,
    request_id      uuid not null references approval_request(request_id),
    state           varchar(16) not null default 'SUSPENDED',
    suspended_at    timestamptz not null default now(),
    resumed_at      timestamptz,
    human_wait_seconds numeric(12,3),
    created_at      timestamptz not null default now(),

    constraint ck_approval_suspension_state
        check (state in ('SUSPENDED','RESUMED','ABANDONED')),
    constraint ck_approval_suspension_wait
        check ((state = 'SUSPENDED') = (resumed_at is null))
);

-- 豁免查询面（RunReconciler suspensionGate）+ 观测面
create unique index uq_approval_suspension_active on approval_suspension(run_id)
    where state = 'SUSPENDED';
create index ix_approval_suspension_run on approval_suspension(run_id, suspended_at);

comment on table approval_suspension is
    'PC-C3：审批挂起台账（§2.10）——同 run 至多一个 SUSPENDED（唯一部分索引）；'
    'human_wait_seconds = wall clock 差（永不冻结的诚实时钟）；'
    'RESUMED/ABANDONED 才有 resumed_at';

grant select, insert, update on approval_suspension to control_app;
