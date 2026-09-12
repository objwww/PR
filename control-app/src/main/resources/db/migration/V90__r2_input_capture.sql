-- ============================================================================
-- V90 —— R2 输入捕获与回放（G3 修补：prompt 原文可复盘）
--
-- 号段说明：原编 V89 与合并窗 BA-122（V89__en07_release_asset_kinds，已入库）
-- 撞号，按号段纪律顺延为 V90（提交前重编号，见执行日志）。
--
-- rca_model_input：RCA 模型调用的输入捕获档（append-only，无 update 授权）。
-- 三档 capture_level（对齐 OTel GenAI capture message content opt-in 惯例）：
--   FULL       原文落库（评测/排障环境 env 覆写）；
--   REDACTED   脱敏文落库（发送前 mask 密钥词形——LangSmith mask 惯例，不事后删）；
--   DIGEST_ONLY 零原文（生产默认；只有 digest 与字节量，账行 prompt_digest 可对账）。
-- 红线：prompt_digest 与 rca_model_call.prompt_digest 恒等（对原始 prompt 算，
-- REDACTED 档存储文本与 digest 天然不等 = 回放器如实标"不完整"，不伪造一致）。
-- CHECK（修补卡原文 `(capture_level='FULL')=(prompt_text is not null)` 与 REDACTED
-- 脱敏文落库语义自相矛盾，按卡内意图改两向钉：FULL 必有文、DIGEST_ONLY 必无文，
-- REDACTED 落脱敏文——偏差登记执行日志）：
--   FULL        ⇒ prompt_text 非空（原文）；
--   REDACTED    ⇒ prompt_text 可空可非空（脱敏文）；
--   DIGEST_ONLY ⇒ prompt_text 必空。
-- 回滚：drop 表（先滚应用后滚库）。
-- ============================================================================

create table rca_model_input (
    id                uuid primary key,
    model_call_id     uuid not null references rca_model_call(id),
    capture_level     text not null,          -- FULL / REDACTED / DIGEST_ONLY
    prompt_text       text,                   -- FULL=原文；REDACTED=脱敏文；DIGEST_ONLY=空
    prompt_digest     char(64) not null,      -- 与 rca_model_call.prompt_digest 对账
    message_bytes     integer,
    approx_tokens     integer,
    redaction_note    text,
    created_at        timestamptz not null,
    constraint ck_rca_model_input_capture check (
        capture_level in ('FULL','REDACTED','DIGEST_ONLY')
        and (capture_level <> 'DIGEST_ONLY' or prompt_text is null)
        and (capture_level <> 'FULL' or prompt_text is not null))
);

create index ix_rca_model_input_call on rca_model_input(model_call_id);

grant select, insert on rca_model_input to control_app;
revoke all on rca_model_input from publisher_app;
revoke all on rca_model_input from public;

comment on table rca_model_input is
    'R2 输入捕获档（append-only；FULL 原文/REDACTED 脱敏/DIGEST_ONLY 仅摘要；digest 恒对账 rca_model_call）';
