-- ============================================================================
-- V16 —— AM4 Evidence/Snapshot（M4-19/20 同任务迁移；技术方案 v1.3 §6 证据与快照条目）
--
--   四正交维度分别校验：schema_version / observed_generation / snapshot_digest /
--   payload_digest。digest 五步纪律的落库前提：payload 与 scope 用 TEXT 存 canonical
--   字节（jsonb 会重排 key/重格式化数字，字节无法原样回读——第④步重算比对必炸）。
--   跨代栅栏：仓储准入面对 evidence.observed_generation == rca_run.generation 判定
--   （同 schema_version 不同 generation 在 DB 面 != 拒绝语义，由 Java 准入面执行）。
--   快照：snapshot_digest 显式列（成员 digest 排序 canonical 后再哈希，不依赖 id 列，
--   见 EvidenceSnapshotBuilder）；冻结 = 快照行+成员行同短事务、无更新路径（迟到证据
--   改不了旧快照）；(run_id, snapshot_digest) 唯一 → 重复冻结幂等。
--   防篡改基线 = 行摘要 + 快照聚合摘要 + DB 权限隔离（不引入 CRC/prev_digest 行链）。
-- ============================================================================

create table rca_evidence (
    id                  uuid         not null,
    run_id              uuid         not null,
    task_id             uuid,
    evidence_type       varchar(32)  not null,
    schema_version      varchar(32)  not null,
    observed_generation bigint       not null,
    source              varchar(128) not null,
    scope               text         not null,
    time_start          timestamptz,
    time_end            timestamptz,
    payload             text         not null,
    payload_digest      varchar(64)  not null,
    created_at          timestamptz  not null default now(),
    constraint pk_rca_evidence primary key (id),
    constraint fk_rca_evidence_run foreign key (run_id) references rca_run (id),
    constraint ck_rca_evidence_generation check (observed_generation >= 0),
    constraint ck_rca_evidence_time
        check (time_start is null or time_end is null or time_start <= time_end)
);

create index ix_rca_evidence_run on rca_evidence (run_id, created_at);

comment on table rca_evidence is
    'AM4 M4-19 证据信封（payload/scope TEXT 存 canonical 字节；读路径重算比对检篡改）';

create table rca_evidence_snapshot (
    id                  uuid        not null,
    run_id              uuid        not null,
    snapshot_digest     varchar(64) not null,
    observed_generation bigint      not null,
    config_digest       varchar(64) not null,
    tool_registry_digest varchar(64) not null,
    parent_snapshot_digest varchar(64),
    created_at          timestamptz not null default now(),
    constraint pk_rca_evidence_snapshot primary key (id),
    constraint fk_rca_evidence_snapshot_run foreign key (run_id) references rca_run (id),
    constraint uq_rca_evidence_snapshot unique (run_id, snapshot_digest)
);

comment on table rca_evidence_snapshot is
    'AM4 M4-20 证据快照（snapshot_digest 显式列；冻结不可变+parent 链；重复冻结幂等）';

create table rca_snapshot_member (
    snapshot_id    uuid        not null,
    evidence_id    uuid        not null,
    evidence_type  varchar(32) not null,
    payload_digest varchar(64) not null,
    constraint pk_rca_snapshot_member primary key (snapshot_id, evidence_id),
    constraint fk_rca_snapshot_member_snapshot foreign key (snapshot_id)
        references rca_evidence_snapshot (id),
    constraint fk_rca_snapshot_member_evidence foreign key (evidence_id)
        references rca_evidence (id)
);

comment on table rca_snapshot_member is
    'AM4 M4-20 快照成员冻结表（仅冻结时插入，无更新路径；digest 身份=type+payload_digest）';

grant select, insert, update on rca_evidence, rca_evidence_snapshot, rca_snapshot_member
    to control_app;
