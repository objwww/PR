-- ============================================================================
-- V19 —— AM4 工具回放账本（M4-32 REPLAY_MOCK 精确匹配的 PG 实现）
--
--   键 = action_digest（envelope 六字段 tool/toolVersion/schemaHash/args/timeRange/
--   inputSnapshotDigest 的 InternalCanonicalJsonV1 canonical sha256）——全相同才同键，
--   任一字段不同即查不到（REPLAY_MISS，绝不返回"最接近"的记录）。
--   同 digest 重复写入 = on conflict do nothing（应用层 ReplayToolGateway.record
--   已强制同键异响应冲突拒绝；本表 DB 层兜底"冲突禁静默覆盖"——首个录制即终版）。
--   迁移编号：v1.3r1 裁定的 AM4 迁移范围为 V12~V18；本表为 M4-32 端口落码时
--   （ToolReplayStore）遗漏的 PG 面补齐，编号顺延 V19（无表可并，一迁移一任务口径不变）。
-- ============================================================================

create table rca_tool_replay (
    id            uuid        not null,
    action_digest varchar(64) not null,
    tool_name     varchar(64) not null,
    tool_version  varchar(32) not null,
    response      bytea       not null,
    created_at    timestamptz not null default now(),
    constraint pk_rca_tool_replay primary key (id),
    constraint uq_rca_tool_replay_digest unique (action_digest)
);

comment on table rca_tool_replay is
    'AM4 M4-32 精确回放账本（键=action digest；同 digest 首录即终版，DB 兜底防静默覆盖）';

grant select, insert on rca_tool_replay to control_app;
