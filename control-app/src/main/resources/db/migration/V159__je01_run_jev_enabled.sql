-- JE-01（方案 research/agent-eval-audit-20260920/JEV接入与上下文选材方案.md §5 步骤6/7）：
-- Jev 增强路径开关冻结列 + 系统级运行时旗标存储。
--
-- rca_run.jev_enabled：铸造时点冻结（三处铸造点读开关随行落列），执行期只读——
-- 切换开关不改变在跑调查（增强模式随调查创建冻结）。存量行默认 false = 现有链路
-- 原样；不做回填 UPDATE（迁移只追加）。
--
-- alert_runtime_flag：页面可写系统旗标（现无任何可写设置表；JEV 开关为第一行）。
-- 铸造点解析顺序 = 本表行 > 配置默认 app.alert.r7.jev.enabled；表不可读回退配置默认
-- （fail-open 到静态面，不阻断铸造）。
--
-- 回滚语义：drop column / drop table（仅新列新表，存量行为零依赖）。

alter table rca_run
    add column jev_enabled boolean not null default false;

create table alert_runtime_flag (
    name varchar(64) primary key,
    enabled boolean not null,
    reason text,
    updated_by varchar(128),
    updated_at timestamptz not null default now()
);
