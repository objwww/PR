-- ============================================================================
-- V99 —— CL-01 检查点提交围栏（告警-Agent闭环修复 v1 §2.1）
--
-- rca_primary_checkpoint 扩列（expand-only，不改历史行语义；旧行 revision=0
-- 仅表示切换时的首个并发版本，不表示旧历史只有一次写入）：
--   revision                真正的并发修订（提交围栏 CAS 谓词；与 decisionSeq
--                           的"动作序"语义分型，不可互代）
--   schema_version          记忆/协议 schema 批次（新 Run 启用新协议用，CL-06）
--   current_context_digest  当前消费上下文摘要锚（CL-08 摘要消费指针，可空）
--   current_summary_id      当前已验证摘要 id（CL-08 摘要消费指针，可空）
--   last_action_key         已落结果的逻辑动作身份（同动作重复提交 REPLAYED 判定）
--   last_action_digest      该动作结果摘要（相同动作不同结果 = 一致性错误留痕）
--
-- 不加 FK/检查约束改动 task/run 关联（历史孤儿先扫描报告，V99 不强行收口）。
-- 新协议（revision CAS writer）仅在兼容读代码部署、旧 writer 排空后启用；
-- 禁止新旧 writer 混跑（旧 upsert 不检查 revision）。
-- 回滚：应用回滚必须选理解本 schema 的兼容构建；库默认只向前修复。
-- 编号纪律：rebase 时以下一可用号为准替换 V99。
-- ============================================================================

alter table rca_primary_checkpoint
    add column revision               bigint      not null default 0,
    add column schema_version         integer     not null default 0,
    add column current_context_digest char(64),
    add column current_summary_id     uuid,
    add column last_action_key        text,
    add column last_action_digest     char(64);

comment on column rca_primary_checkpoint.revision is
    'CL-01 提交围栏并发修订（条件 UPDATE 谓词；与 decision_seq 动作序分型不可互代）';
comment on column rca_primary_checkpoint.last_action_key is
    'CL-01 已落结果的逻辑动作身份（同动作重复提交 REPLAYED；仅覆盖最近一动作，更早重放由 revision 栅栏拦截）';
