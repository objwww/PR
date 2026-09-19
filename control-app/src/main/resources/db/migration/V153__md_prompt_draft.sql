-- V153 — M-d T7 提示词草稿工作面（子 agent 提示词修改/发布/diff 流程的后端落点）
--
--   设计（M-d 方案 §7）：草稿 = 提案文档，不是 release_asset 行——role_digest 是
--   AgentProfile 全量摘要（prompt+allowlist+预算+schema），草稿期无法也不得伪造；
--   发布路径保持既有受控激活（ConfigBundle 激活 + rca_model_call.role_digest 对账），
--   本表只承载"起草→对照→裁定"工作流，不做绕行发布。
--
--   状态机：DRAFT →（discard）DISCARDED /（应用后回填）APPLIED。
--   base 快照随起草冻结（base_template/base_asset_digest）——diff 基线不漂移。
--
--   回滚：drop table prompt_draft;

create table prompt_draft (
    id                  uuid primary key,
    role                text not null,
    base_role_version   text not null,
    base_asset_digest   char(64) not null,
    base_template       text not null,
    proposed_template   text not null,
    author              text not null,
    status              varchar(16) not null default 'DRAFT',
    applied_asset_digest char(64),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),

    constraint ck_prompt_draft_status check (status in ('DRAFT', 'DISCARDED', 'APPLIED')),
    constraint ck_prompt_draft_proposed check (length(proposed_template) > 0),
    constraint ck_prompt_draft_applied_digest
        check (status <> 'APPLIED' or applied_asset_digest is not null)
);

create index ix_prompt_draft_role on prompt_draft (role, created_at desc);

comment on table prompt_draft is
    'M-d T7 提示词草稿工作面（起草→对照→裁定；发布仍走既有受控激活，不做绕行直发）';
comment on column prompt_draft.base_template is
    '起草时刻的现行模板快照（diff 基线不随上游漂移）';

grant select, insert, update on prompt_draft to control_app;
