-- ============================================================================
-- V165 —— ME-T12b（D09）：评测预登记冻结面 eval_preregistration
--
--   发布验收的判定锚跑批之前冻结：min_clusters（独立簇数下限，与
--   PairedTrialStats.MIN_CLUSTERS 门阈同源）随 run 落登记行，跑完不得再挑
--   最有利阈值。ReleaseAcceptanceEvaluator 质量面 SMALL_SAMPLE 判定只认本表
--   登记值——登记缺席 = 锚不可得，质量面如实 INCONCLUSIVE
--   （PREREGISTRATION_MISSING），不拿当前代码常量冒充"当时登记的阈值"。
--
--   键面：eval_run_id 外键指向 eval_run(id)；uq(eval_run_id)——一 run 一登记，
--   重放/重跑同 run 幂等（on conflict do nothing），登记永不改写（insert-only）。
--   prereg_digest = sha256(runId|minClusters|registeredAt 截断到秒)——登记内容
--   自证锚，读面随验收结论透出供复核。
--
--   写点与身份：登记在批开始 eval_run 落行后立写（EvalBatchRunner.runBatch，
--   eval worker 链，写身份 = eval_app——与 eval_run 写身份同源；受理 HTTP 链
--   （control_app）早于 run 行存在，外键次序决定写点只能在批起始）。读面 =
--   比较服务（control_app 查询身份）→ grant select。零 update/delete 开口。
--
--   回滚：drop table eval_preregistration;
-- ============================================================================

create table eval_preregistration (
    id                uuid primary key,
    eval_run_id       uuid not null references eval_run (id),
    min_clusters      integer not null,
    prereg_digest     varchar(64) not null,
    registered_at     timestamptz not null default now(),

    constraint uq_eval_preregistration unique (eval_run_id)
);

comment on table eval_preregistration is
    'ME-T12b（D09）评测预登记（insert-only；uq(run) 一 run 一登记不改写；min_clusters=独立簇数下限判定锚，与 PairedTrialStats.MIN_CLUSTERS 门阈同源，登记缺席=质量面如实 INCONCLUSIVE 不猜）';
comment on column eval_preregistration.prereg_digest is
    'sha256(runId|minClusters|registeredAt 截断到秒)——登记内容自证锚';

grant select, insert on eval_preregistration to eval_app;
grant select on eval_preregistration to control_app;
