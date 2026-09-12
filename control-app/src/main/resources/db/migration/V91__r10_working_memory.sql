-- ============================================================================
-- V91 —— R10 工作记忆快照与检查点记忆关联（MA-02：记忆不是新证据）
--
-- rca_working_memory：工作记忆快照独立表（R7 方案 §19.2 三层存储——不落 evidence
-- 防进 validRefs，不另建聊天库）。append-only 深冻结（MC06：外部修改候选不影响
-- 已提交快照/digest）：UNIQUE(run_id, task_id, checkpoint_revision) + 插入幂等，
-- 同修订重放返回既有行不覆盖（MC08 同律：迟到/重复 upsert 不漂移已提交面）。
-- 槽契约：memory_json = {hypotheses, ruled_out, counter_evidence_refs, open_gaps}
-- （facts 是"带支持引用的判断"，与原始 Observation 保持类别区分；已推翻假设
--   保留理由不删除，防下轮重新猜——MC05）。
-- memory_digest = memory_json 规范化 JSON 的 sha256（深冻结校验锚）。
-- config_epoch 可空：装配点未接入代际面时如实 null（不编造）。
-- 检查点关联（§19.2"现有 PrimaryCheckpoint 关联 current_memory_id 即可"）：
-- rca_primary_checkpoint 增 memory_id/memory_digest 两可空列——检查点钉住
-- "本步输入所用记忆快照"；崩溃恢复读同快照，不额外生成另一版（MC07）。
-- 回滚：drop 表 + alter table rca_primary_checkpoint drop column 两列。
-- 号段：修补方案原编 V90，因 R2 输入捕获顺延占 V90，本卡按下一可用号 V91 落位。
-- ============================================================================

create table rca_working_memory (
    id                  uuid primary key,
    run_id              uuid not null references rca_run(id),
    task_id             uuid not null references rca_task(id),
    checkpoint_revision bigint not null,
    memory_json         jsonb not null,   -- hypotheses/ruled_out/counter_refs/open_gaps
    memory_digest       char(64) not null,
    config_epoch        bigint,
    created_at          timestamptz not null,
    constraint uq_rca_working_memory_revision unique (run_id, task_id, checkpoint_revision)
);

-- 计划清单原列非唯一索引 ix_rca_working_memory_task(run_id,task_id,checkpoint_revision)；
-- uq 同列集自带索引，二者取一（偏差登记：唯一约束兼任查询索引，防重复索引）。

grant select, insert on rca_working_memory to control_app;
revoke all on rca_working_memory from publisher_app;
revoke all on rca_working_memory from public;

alter table rca_primary_checkpoint add column memory_id uuid;
alter table rca_primary_checkpoint add column memory_digest char(64);

comment on table rca_working_memory is
    'R10 工作记忆快照（append-only 深冻结；修订唯一=同修订重放不覆盖；记忆不是新证据）';
