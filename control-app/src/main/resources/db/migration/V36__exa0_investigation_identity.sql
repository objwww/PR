-- ============================================================================
-- V36 —— EX-A0 身份与执行契约冻结（docs/告警-EXA0-身份与执行契约.md §5）
--
--   三 digest 分列（F04）：config_digest（V25 已有）/ investigation_input_digest
--   （本迁移新增）/ evidence_snapshot_digest（= rca_evidence_snapshot.snapshot_digest，
--   V16 已有）。调查输入身份+冻结时间窗（F14）由铸点（IncidentProjector/
--   RcaRunOrchestrator castRunAndTask）随 insertRouted 一次落列，Run 创建时冻结，
--   执行期只读；执行器对空列（存量行/部署缝隙）回退旧行为并 WARN——不回填（无可信
--   回填源），新铸 run 一律有值。
--   动作身份三元组（attempt/call_seq/action_seq）：attempt=rca_attempt（V7 已有）、
--   call_seq=rca_tool_invocation.call_seq（V15 已有）；action_seq 为 EX-A0 冻结的
--   契约列（同 (run,task,attempt) 内逻辑动作序），可空，接线归 EX-A4a F16。
--   回滚：drop 四列两约束（先滚应用后滚库——应用对缺列行有回退路径）。
--   编号纪律：rebase 时以下一可用号为准替换 V36。
-- ============================================================================

alter table rca_run
    add column investigation_input_digest char(64),
    add column window_start timestamptz,
    add column window_end   timestamptz;

alter table rca_run
    add constraint ck_rca_run_window
    check ((window_start is null and window_end is null) or
           (window_start is not null and window_end is not null
                and window_start <= window_end));

alter table rca_tool_invocation add column action_seq bigint;

alter table rca_tool_invocation
    add constraint ck_rca_tool_invocation_action_seq
    check (action_seq is null or action_seq >= 0);

comment on column rca_run.investigation_input_digest is
    'EX-A0 调查输入身份 digest（investigation-input.v1 canonical sha256；铸点冻结，执行期只读）';
comment on column rca_run.window_start is
    'EX-A0 冻结调查时间窗起点（铸造时刻-600s，F14；执行期禁止改取执行时窗口）';
comment on column rca_run.window_end is
    'EX-A0 冻结调查时间窗终点（铸造时刻，F14）';
comment on column rca_tool_invocation.action_seq is
    'EX-A0 契约列：同 (run,task,attempt) 内逻辑动作序（物理请求=call_seq）；接线归 EX-A4a F16';
