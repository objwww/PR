-- ============================================================================
-- V89 —— EN-07 固定语料 kind 扩集（release_asset 资产词表补迁移；BA-122）
--
--   缺口：EN-07 产品域 ReleaseAsset 五 kind 词表（PROMPT/SKILL/TOOL_SCHEMA +
--   RUNBOOK_DOC/RUNBOOK_CATALOG，"加法扩 kind，EN-01 三类同律"）在合并窗入
--   main 时，DB 侧 check 仍停在 V60 三 kind——产品写 RUNBOOK_DOC 即撞
--   release_asset_asset_kind_check（195 真 PG 首证：En07/En10 真值 IT 双红 +
--   RunbookCorpusPublisher 发布面同路径，生产必然复发）。
--
--   修法：原位扩集（V12 ck_rca_run_finish 同律：drop + add 同名约束）；
--   身份面 (kind, digest) 与 immutable 授权面零改动。
-- ============================================================================

alter table release_asset drop constraint release_asset_asset_kind_check;
alter table release_asset add constraint release_asset_asset_kind_check
    check (asset_kind in ('PROMPT', 'SKILL', 'TOOL_SCHEMA',
                          'RUNBOOK_DOC', 'RUNBOOK_CATALOG'));

comment on constraint release_asset_asset_kind_check on release_asset is
    'EN-07（V89）扩集：runbook 语料两 kind（文档正文 + 目录快照）与 EN-01 三类同律不可变';
