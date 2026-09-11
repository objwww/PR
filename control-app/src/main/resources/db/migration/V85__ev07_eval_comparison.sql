-- ============================================================================
-- V85 —— EV-07 配对工作台：对比结论与质量门持久化（docs/告警-评测中心与能力版本演进-
--   审查及详细改造方案-v1.md EV-07 卡 / §3.5 / §5.3 "GET /api/eval/comparisons……
--   缺持久化先补记录"）
--   eval_comparison    一次基线×候选对比的落档快照：可比性维度清单 + 配对计数
--                      （改善/退化/持平/单侧缺席）+ PairedTrialStats 溯源快照 +
--                      对比质量门结论（eval-compare-gate-v1 规则版本锚）
--
-- 号段纪律：V80~V83 已占用，V84 空置（EV-05 零迁移声明），EV-07 自 V85 起。
--
-- 语义纪律（V10 eval_case_result / EvaluationRecordV1 同律）：
--   - insert-only：落档即冻结，重复落档 = 换新 id 一行（不覆盖旧结论）；
--     "生效面" = 同 (baseline,candidate) 对最新一行（created_at DESC, id DESC）；
--   - gate_outcome NOT_EVALUABLE = 可比性检查未过（不出配对结论，如实）；
--   - stats_snapshot NULL = 本对比无配对案例（不伪造统计面，INV-AM5-3 同律）；
--   - 非 PASS 结论 gate_reasons 必非空（门禁解释完整性，EvaluationRecordV1 同律）。
--
-- 授权分工（V45/V81 同律）：
--   - control_app（/api/eval 面 HTTP 身份）：select,insert——对比计算与落档都在
--     control-app 读面内完成（输入只读 eval_run/eval_case_result/case_version），
--     零 update/delete 开口；
--   - eval_app/publisher/notify/arena/chaos/public：显式 revoke 归零（对比结论是
--     control 面读侧产物，eval-runner 与生产角色无消费面）。
-- ============================================================================

create table eval_comparison (
    id                uuid primary key,
    baseline_run_id   uuid not null references eval_run(id),
    candidate_run_id  uuid not null references eval_run(id),

    comparable        boolean not null,        -- 严格维度（数据集/输入快照/规则等）全等
    dimension_diffs   jsonb not null           -- 可比性维度清单快照（含 equal 标记与两侧取值）
                      check (jsonb_typeof(dimension_diffs) = 'array'),

    paired_count      integer not null check (paired_count >= 0),
    unpaired_count    integer not null check (unpaired_count >= 0),
    improved_count    integer not null check (improved_count >= 0),
    regressed_count   integer not null check (regressed_count >= 0),
    flat_count        integer not null check (flat_count >= 0),

    stats_snapshot    jsonb,                   -- PairedTrialStats 溯源快照；NULL=无配对
    gate_outcome      varchar(16) not null
                      check (gate_outcome in
                          ('PASS', 'FAIL', 'INCONCLUSIVE', 'NOT_EVALUABLE')),
    gate_reasons      jsonb not null default '[]'::jsonb
                      check (jsonb_typeof(gate_reasons) = 'array'),
    gate_rule_version text not null,           -- 门规则版本锚（eval-compare-gate-vN）

    actor             varchar(64) not null,    -- 落档主体（认证面唯一来源）
    created_at        timestamptz not null default now(),

    constraint ck_eval_comparison_distinct_runs
        check (baseline_run_id <> candidate_run_id),
    -- 门禁解释完整性（EvaluationRecordV1 同律）：PASS 零原因，非 PASS 必带原因
    constraint ck_eval_comparison_gate_reasons check (
        (gate_outcome = 'PASS' and jsonb_array_length(gate_reasons) = 0)
        or (gate_outcome <> 'PASS' and jsonb_array_length(gate_reasons) > 0))
);

comment on table eval_comparison is
    'EV-07 配对工作台对比结论落档（insert-only；同对最新行 = 生效面；NOT_EVALUABLE = 可比性未过不出配对结论）';

-- 生效面投影：同对最新一行 / 某 run 作为候选的最新落档（EV-03 qualityVerdict 分面源）
create index ix_eval_comparison_pair on
    eval_comparison(baseline_run_id, candidate_run_id, created_at desc, id desc);
create index ix_eval_comparison_candidate on
    eval_comparison(candidate_run_id, created_at desc, id desc);

-- ---------- 授权（V10/V81 同构；IT 干净库缺角色兜底同 V10） ----------

grant select, insert on eval_comparison to control_app;

revoke all on eval_comparison from publisher_app, notify_app, public;
do $$
begin
    if exists (select from pg_roles where rolname = 'eval_app') then
        revoke all on eval_comparison from eval_app;
    end if;
    if exists (select from pg_roles where rolname = 'arena_app') then
        revoke all on eval_comparison from arena_app;
    end if;
    if exists (select from pg_roles where rolname = 'chaos_admin_app') then
        revoke all on eval_comparison from chaos_admin_app;
    end if;
end
$$;
