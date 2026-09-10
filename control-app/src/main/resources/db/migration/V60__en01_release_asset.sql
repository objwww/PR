-- ============================================================================
-- V60 —— EN-01 发布资产（release 域；增强线 Tool-MCP-RAG-Skill v1.1 §8.2 数据契约）
--   release_asset  Prompt/Skill/工具 schema 三类资产的统一不可变存储：
--                  身份 = (asset_kind, asset_digest)，digest = 内容 canonical
--                  sha256（内容寻址：重发幂等 + 篡改即新身份，S10 锚）。
--                  密钥不入资产（INV-AM5-5）——键名扫描 fail-closed 归发布面，
--                  DB 面只锁对象形状（V24 同律）。
--
-- 号段说明：三线定死 R7=V46 起 / EN=V60 起 / EV-DR=V80 起（2026-09-11 用户裁定，
-- 增强线方案 §6.1）；V46~V59 留 R7 线，本表为 EN 线首迁移。
-- ============================================================================

create table release_asset (
    asset_kind    text not null check (asset_kind in ('PROMPT', 'SKILL', 'TOOL_SCHEMA')),
    asset_digest  char(64) not null,
    content       jsonb not null check (jsonb_typeof(content) = 'object'),
    created_by    text not null,
    created_at    timestamptz not null,
    constraint uq_release_asset_kind_digest unique (asset_kind, asset_digest)
);

comment on table release_asset is
    'EN-01 发布资产（PROMPT/SKILL/TOOL_SCHEMA）：身份=内容 digest；release_manifest 依赖闭包的解析目标面';
comment on column release_asset.asset_digest is
    'canonical content 的 sha256（internal-v1 规范化 JSON，键序无关）——注册幂等锚 + 内容寻址身份（显示版本标签不参与身份）';

-- ---------- 授权（V24 惯例：immutable 面 + 显式冻结） ----------

grant select, insert on release_asset to control_app;
revoke update, delete on release_asset from control_app;

revoke all on release_asset
    from publisher_app, notify_app, eval_app, public;
