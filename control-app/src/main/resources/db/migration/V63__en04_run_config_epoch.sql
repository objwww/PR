-- ============================================================================
-- V63 —— EN-04 运行中热更新：配置代际史 + 命令账本扩容（增强线方案 §185/§207/§227）
--
-- 1) rca_run_config_epoch：config_epoch → release_digest 的追加历史（§185 "Run
--    初始 bundle 不可改写；单独记录追加历史"）。主键 (run_id, config_epoch) 即
--    §207 要求的 UNIQUE(run_id, config_epoch)——并发双切换败者在 insert 现形
--    （应用事务整体回滚为 REJECTED_STALE，H05/H10 commit-once）。
--    准入播种：insertRouted 携带 configDigest 的 run 同事务播种 epoch=0
--    （source_command_id 空、applied_by='ADMISSION'）；存量 run 无史行 = 切换面
--    fail-closed 拒绝。追加史 immutable：control_app 只 select,insert。
-- 2) operator_command 扩容复用（§227 "既有command/event表可扩展复用"）：
--    command_type + 'CONFIG_SWITCH'；state 扩 varchar(24) + 'WAITING_SAFE_POINT'
--    （非终态中转）/'EXPIRED'（H14 deadline 无安全点）/'CANCELLED'（H15 待命令
--    撤回）。切换专有字段（expected_config_epoch/target_release_digest/reason/
--    deadline）随 payload jsonb 携带（正文列落库后零开口的既有契约，白名单解析
--    见 ConfigSwitchRequest）——state/applied_at 列级授权零改动。
-- 回滚：drop 表 + 还原约束（先滚应用后滚库）。
-- 编号纪律：rebase 时以下一可用号为准替换 V63。
-- ============================================================================

create table rca_run_config_epoch (
    run_id            uuid not null references rca_run (id),
    config_epoch      bigint not null,
    release_digest    char(64) not null,       -- 本代际钉死的路由 bundle digest
    source_command_id uuid,                    -- 来源命令行；epoch 0 准入播种为空
    applied_by        varchar(64) not null,    -- 'ADMISSION' 或运维主体
    reason            text,
    created_at        timestamptz not null default now(),

    constraint pk_rca_run_config_epoch primary key (run_id, config_epoch),
    constraint ck_rca_run_config_epoch check (config_epoch >= 0)
);

comment on table rca_run_config_epoch is
    'EN-04 配置代际追加史（UNIQUE(run_id,config_epoch)；热更新只追加不覆盖；MIXED_CONFIG=史行>1）';

-- 授权（V46 绑定同构）：追加史 immutable
grant select, insert on rca_run_config_epoch to control_app;
revoke update, delete on rca_run_config_epoch from control_app;
revoke all on rca_run_config_epoch from publisher_app, notify_app, eval_app, public;

-- ---------- operator_command 扩容（V27 同列名纪律） ----------

alter table operator_command
    alter column state type varchar(24);

alter table operator_command drop constraint operator_command_state_check;
alter table operator_command add constraint operator_command_state_check
    check (state in ('PERSISTED', 'WAITING_SAFE_POINT', 'APPLIED',
                     'REJECTED_STALE', 'REJECTED_FORBIDDEN', 'EXPIRED',
                     'CANCELLED'));

alter table operator_command drop constraint operator_command_command_type_check;
alter table operator_command add constraint operator_command_command_type_check
    check (command_type in ('CANCEL', 'HINT', 'FEEDBACK', 'CONFIG_SWITCH'));

comment on column operator_command.command_type is
    'EN-04 扩容：CONFIG_SWITCH 热更新命令（专有字段随 payload 白名单携带，§227）';
