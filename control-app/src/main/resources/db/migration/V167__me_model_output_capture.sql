-- ============================================================================
-- V167 —— ME 输出捕获（R2 输入捕获 V90 的对称面：模型响应可回放/可展示）
--
-- 背景：A/B 对照实验要求每一步模型推理的回答可展示；此前输出只有 action
-- 轨迹不落库。本迁移完全复制 V90 rca_model_input 的列族与授权面，加式扩展。
--
-- rca_model_output：RCA 模型调用的输出捕获档（append-only，无 update 授权）。
-- 捕获档位与输入同族（app.alert.r7.output-capture，默认 off=零行档——
-- OFF 不落任何行，故不进 CHECK 值域；A/B 实验窗覆写 full/redacted）：
--   FULL       原文落库（评测/对照实验环境 env 覆写）；
--   REDACTED   脱敏文落库（落库前 mask 密钥值——与输入捕获同款掩敏器）；
--   DIGEST_ONLY 零原文（只有 digest 与字节量）。
-- 红线：output_digest 恒为对原始响应文本算的 sha256——REDACTED 档存储文本
-- 与 digest 天然不等 = 读面如实标"已掩敏"，不伪造一致（脱敏不可反推原文）。
-- 失败路径语义：FAILED/TIMEOUT 调用无输出产出 → 不落行（诚实"无输出"，
-- 不伪造错误文本）；故本表只对 SUCCESS 调用有行（输入捕获"发送前必落"
-- 不对称是因输入恒存在，输出仅成功时存在）。
-- CHECK 两向钉（同 V90 口径）：
--   FULL        ⇒ output_text 非空（原文）；
--   REDACTED    ⇒ output_text 可空可非空（脱敏文）；
--   DIGEST_ONLY ⇒ output_text 必空。
-- 回滚：drop 表（先滚应用后滚库）。
-- ============================================================================

create table rca_model_output (
    id                uuid primary key,
    model_call_id     uuid not null references rca_model_call(id),
    capture_level     text not null,          -- FULL / REDACTED / DIGEST_ONLY
    output_text       text,                   -- FULL=原文；REDACTED=脱敏文；DIGEST_ONLY=空
    output_digest     char(64) not null,      -- 对原始响应文本的 sha256（掩文 ≠ 摘要）
    message_bytes     integer,
    approx_tokens     integer,
    redaction_note    text,
    created_at        timestamptz not null,
    constraint ck_rca_model_output_capture check (
        capture_level in ('FULL','REDACTED','DIGEST_ONLY')
        and (capture_level <> 'DIGEST_ONLY' or output_text is null)
        and (capture_level <> 'FULL' or output_text is not null))
);

create index ix_rca_model_output_call on rca_model_output(model_call_id);

grant select, insert on rca_model_output to control_app;
revoke all on rca_model_output from publisher_app;
revoke all on rca_model_output from public;

comment on table rca_model_output is
    'ME 输出捕获档（append-only；FULL 原文/REDACTED 脱敏/DIGEST_ONLY 仅摘要；仅 SUCCESS 调用有行，digest 恒为原始响应摘要）';
