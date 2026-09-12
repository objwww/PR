-- ============================================================================
-- V98 —— EN-01/03 contextPolicyDigest 资产 kind 扩集（release_asset 词表第七 kind）
--
--   缺口（2026-09-12 审计第四层确认）：增强线 v1.1 L464 规格"EN-01/03 同时登记并
--   固定 contextPolicyDigest、compactionPromptDigest、摘要 schema 版本……没有
--   LLM 摘要时也必须固定确定性裁剪策略，保证重放能解释当时模型看到了什么"——
--   compactionPromptDigest 已随 P0 批落 release_asset（PROMPT kind，directive+
--   policy 合一内容 ff846b82…），但**确定性裁剪策略没有独立资产**，replay 无法
--   按 digest 反查"当时第一刀怎么裁"。
--
--   修法：原位扩集（V89 同律 drop+add 同名约束）+ 应用侧 ContextAssembler 静态
--   policyAsset() 构造（限长全部为编译期常量，单源）+ AlertAm4Config 启动登记并
--   log 双 digest（contextPolicyDigest + compactionPromptDigest = 重放解释锚对）。
--   身份面 (kind, digest) 与 immutable 授权面零改动。
-- ============================================================================

alter table release_asset drop constraint release_asset_asset_kind_check;
alter table release_asset add constraint release_asset_asset_kind_check
    check (asset_kind in ('PROMPT', 'SKILL', 'TOOL_SCHEMA',
                          'RUNBOOK_DOC', 'RUNBOOK_CATALOG', 'CONTEXT_POLICY'));

comment on constraint release_asset_asset_kind_check on release_asset is
    'EN-01/03（V98）扩集：CONTEXT_POLICY=确定性裁剪策略资产（R1 界面限长单源钉版；无 LLM 摘要时重放解释的唯一依据）';
