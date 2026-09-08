-- V32（AM6 M6-02 落码方案 M6-02 节）：engine_comparison 引擎对照结论表。
-- 号段勘误：落码方案原定 V31 号位——V31 已被 BA-53（canary_route_decision FK
-- DEFERRABLE 修复，commit 75c9298）占用，AM6 后续迁移顺延一号（V32~V34），
-- 详见 PROGRESS 2026-09-08 M6-01 四案收官条目。
--
-- 语义（落码方案 DDL 要点 + 架构 v1.2:736/739）：
--   * 一次引擎对照 = 一行：native 侧 run + comparison_key（两侧执行身份+snapshot+
--     候选 digest 的 canonical hash）；M6-02 由 AM4 影子触发器在 HOLMES 主路径
--     run 完成后落影子对照，M6-05 反向影子复用本表（shadow_execution_id 彼时再加）。
--   * insert-only 证据表：uq_ec_pair (native_run_id, comparison_key) = 同对照
--     幂等锚（重放/重复触发不重记，仓储 ON CONFLICT DO NOTHING）。
--   * 无 GT 只记 disagreement 不判对错（架构 :736）：disagree_flags 六维 =
--     架构 :739 冻结的实时可比面 schema / tool_legality / latency / cost /
--     safety_violation / result；任一侧缺数的维度不标记（缺数≠差异）。
--   * native_run_id 不加 FK：对照行是观察面结论（影子 run 身份以
--     shadow_exec_ref 审计引用留痕），不与 run 行生命周期绑定。

create table engine_comparison (
    id               bigserial primary key,
    native_run_id    uuid        not null,
    comparison_key   char(64)    not null,            -- 两侧执行身份+snapshot+候选 digest 的 canonical hash
    shadow_exec_ref  text        not null,            -- M6-02 AM4 shadow 或 M6-05 shadow work 的审计引用
    snapshot_digest  char(64)    not null,            -- 同一冻结快照（FUT-06）
    holmes_outcome   jsonb,                           -- Holmes 侧结论摘要（脱敏）
    native_outcome   jsonb,
    disagree_flags   jsonb       not null default '[]', -- 六维差异标记；无 GT 只记 disagreement 不判对错（架构 :736）
    noise_baseline   jsonb,                           -- Holmes 自比对底噪引用（E-20；M6-05 底噪校准回填）
    cost_compare     jsonb,                           -- token/时长双侧
    created_at       timestamptz not null default now(),
    constraint uq_ec_pair unique (native_run_id, comparison_key)
);

comment on table engine_comparison is
    'AM6 M6-02 引擎对照结论（insert-only）：HOLMES 主路径 vs Native 影子/候选的归一化双侧结论与六维差异标记';
comment on column engine_comparison.native_run_id is
    'Native 侧 run 身份（M6-02=AM4 影子 run；无 FK——观察面结论不绑 run 生命周期）';
comment on column engine_comparison.comparison_key is
    'sha256(canonical(holmes_run_id, native_run_id, snapshot_digest, candidate_digest))，同对照幂等锚';
comment on column engine_comparison.shadow_exec_ref is
    '影子执行审计引用（M6-02 am4-shadow-trigger / M6-05 V33 shadow work id）';
comment on column engine_comparison.snapshot_digest is
    '双侧共用的冻结证据快照 digest（FUT-06 同一快照前提）';
comment on column engine_comparison.holmes_outcome is
    'Holmes 侧归一化结论摘要（validation/root_cause/claims/latency/tokens；脱敏）';
comment on column engine_comparison.native_outcome is
    'Native 侧归一化结论摘要（同 holmes 形状；影子侧 validation/tokens 缺数为 null）';
comment on column engine_comparison.disagree_flags is
    '六维差异标记数组 [{dim,holmes,native}]；架构 :739 冻结维度，缺数维度不标记（无 GT 不判对错）';
comment on column engine_comparison.noise_baseline is
    'Holmes 自比对底噪引用（E-20；M6-05 底噪校准前为 null）';
comment on column engine_comparison.cost_compare is
    '成本对照（双侧 total_tokens/latency_ms，缺侧省略键）';

-- ---------- 授权（V7/V25/V30 惯例：证据表只 select,insert） ----------

grant select, insert on engine_comparison to control_app;
revoke update, delete on engine_comparison from control_app;
revoke all on engine_comparison
    from publisher_app, notify_app, eval_app, public;
-- BA-42①（195 真 PG 实证：append 走 bigserial 默认值，表授权不覆盖序列面，
-- 漏 USAGE 即生产写路径 permission denied）
grant usage on sequence engine_comparison_id_seq to control_app;
revoke all on sequence engine_comparison_id_seq
    from publisher_app, notify_app, eval_app, public;
