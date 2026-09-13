-- ============================================================================
-- V100 —— CL-05 Skill 每 Run 持久绑定（告警-Agent闭环修复 v1 §4.1）
--
-- rca_run_skill_binding：每 (run, role, epoch) 至多一条的选择事实行。rca_skill_candidate
-- 管候选生命周期，不兼任每 Run 选择；本表把"这个 run 这一角色这一代际用了哪个 Skill
-- （或明确 NONE）"钉成持久事实——重启不漂移、发布不追溯、并发双首选取一。
--
-- 语义（§4.1/§4.2）：
--   * 主键 (run_id, role_id, config_epoch) = 联合唯一选择范围；config_epoch=0 兼容
--     无代际史的存量 run（服务层把 null 代际映射为 0；EN-04 run 准入播种也从 0 起）。
--   * selection_status：SELECTED / NONE——无匹配同样必须持久化（钉空不追溯）。
--   * asset_digest：SELECTED 必填（指向不可变 SKILL 资产）、NONE 必空（CHECK 钉）。
--   * release_digest + selector_version：当次选择的组合与算法版本（审计/重放解释）。
--   * source_command_id：热切来源命令（CONFIG_SWITCH 应用事务同事务生成时填）；
--     首次装配懒生成时为 NULL。
--   * insert-if-absent 单写者：并发双首次撞主键，败者读胜者返回；行落库后不改写。
-- 授权：control_app 仅 select+insert（无 update——绑定事实只追加不修订）；模型
-- 连接身份零授权。回滚：drop table rca_run_skill_binding。
-- 号段：V99 已占（CL-01 提交围栏），本卡按下一可用号 V100 落位。
-- ============================================================================

create table rca_run_skill_binding (
    run_id             uuid not null,
    role_id            text not null,
    config_epoch       bigint not null default 0,
    selection_status   text not null,
    asset_digest       char(64),
    release_digest     char(64) not null,
    selector_version   text not null,
    source_command_id  uuid,
    created_at         timestamptz not null,

    constraint pk_rca_run_skill_binding primary key (run_id, role_id, config_epoch),
    constraint ck_rsb_status check (selection_status in ('SELECTED', 'NONE')),
    constraint ck_rsb_digest_status check (
        (selection_status = 'SELECTED' and asset_digest is not null)
        or (selection_status = 'NONE' and asset_digest is null))
);

grant select, insert on rca_run_skill_binding to control_app;
revoke all on rca_run_skill_binding from publisher_app;
revoke all on rca_run_skill_binding from public;

comment on table rca_run_skill_binding is
    'CL-05 Skill 每 Run 持久绑定：每 (run,role,epoch) 至多一条（SELECTED 或明确 NONE）；insert-if-absent 单写者，行落库后不改写';
