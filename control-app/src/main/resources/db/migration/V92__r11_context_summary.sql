-- ============================================================================
-- V92 —— R11 上下文摘要（MA-04：LLM 压缩生命周期，默认关、待 MC34 放量）
--
-- rca_context_summary：压缩摘要不可变档（R7 方案 §19.4 L444 字段清单 + 正文两列）。
-- 压缩顺序契约：确定性裁剪永远第一刀（R1 界面：证据≤20/轨迹≤8/槽≤10/摘要截断，
-- 恒先生效），本表只承载"确定性裁剪后仍超软阈值"时的 LLM 摘要产物
-- （recall-first，Anthropic context engineering 法）；enabled=false 时零写入。
-- uq(run_id, task_id, source_snapshot_digest)：同一冻结源至多一份已提交摘要——
-- 竞态原子性（CAS 语义：并发同源双写一胜一拒）+ 幂等重放（同律 MC06/MC08）；
-- 次数上限（首期 2 次/run）由服务面 count 计 gate，不入 DB 约束。
-- 字段对齐 §19.4 L444：summaryId/schemaVersion/sourceSnapshotDigest/eventSeqFrom/
-- eventSeqTo/summaryPromptDigest/model/tokenBefore/tokenAfter/requiredRefs jsonb/
-- omittedRefs jsonb/validationResult/producer/configEpoch/created_at；
-- summary_text/summary_digest 两列为本卡补全（正文不在清单=摘要不可回放，
-- MC20/MC33 需要"压缩前后可查"，故正文+sha256 落档；偏差登记执行日志）。
-- required_refs 由宿主生成（绑定 inputRefs ∪ 检查点终局 evidence_refs），
-- 摘要模型不得删空（§19.4 L444）；omitted_refs = 值域全集−保留集（如实留痕）。
-- 压缩动作身份：COMPACTION 占 rca_model_call.action_seq 保留段（≥1_000_000，
-- 决策序首期为两位数量级不撞唯一键；无独立 purpose 列，偏差登记）。
-- 授权：control_app select,insert（不可变档，无 update/delete）；
-- publisher_app/notify_app/eval_app 不授（压缩面不进评测/通知域）。
-- 回滚：drop table rca_context_summary。
-- 号段：修补方案原编 V91，因 R10 工作记忆顺延占 V91，本卡按下一可用号 V92 落位。
-- ============================================================================

create table rca_context_summary (
    id                      uuid primary key,
    run_id                  uuid not null references rca_run(id),
    task_id                 uuid not null references rca_task(id),
    schema_version          int not null,
    source_snapshot_digest  char(64) not null,
    event_seq_from          bigint not null,
    event_seq_to            bigint not null,
    summary_prompt_digest   char(64) not null,
    model                   text not null,
    token_before            int not null,
    token_after             int not null,
    required_refs           jsonb not null,
    omitted_refs            jsonb not null,
    summary_text            text not null,
    summary_digest          char(64) not null,
    validation_result       text not null,
    producer                text not null,
    config_epoch            bigint,
    created_at              timestamptz not null,
    constraint uq_rca_context_summary_source
        unique (run_id, task_id, source_snapshot_digest)
);

grant select, insert on rca_context_summary to control_app;
revoke all on rca_context_summary from publisher_app;
revoke all on rca_context_summary from public;

comment on table rca_context_summary is
    'R11 上下文压缩摘要（不可变档；同源唯一=CAS 提交；确定性裁剪第一刀，LLM 摘要殿后默认关）';
