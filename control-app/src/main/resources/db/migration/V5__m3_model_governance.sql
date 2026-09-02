-- V5__m3_model_governance.sql
-- M3 模型治理：两段记账 + 双路由 fallback + 熔断 + 成本账本（§4.1）

CREATE TABLE model_call_ledger (
    id                     uuid PRIMARY KEY,
    invocation_id          uuid not null,
    call_seq               int  not null CHECK (call_seq >= 1),
    review_run_id          uuid not null REFERENCES review_run(id),
    run_step_id            uuid not null REFERENCES run_step(id),
    attempt_id             uuid not null REFERENCES step_attempt(id),
    lease_epoch            bigint not null,
    route_id               text not null,
    route_role             text not null CHECK (route_role IN ('PRIMARY','FALLBACK')),
    fallback_from          text,
    endpoint_scope         text not null,
    quota_scope            text not null,
    requested_model        text not null,
    reported_model         text,
    provider_request_id    text,
    state                  text not null CHECK (state IN ('STARTED','SUCCEEDED','FAILED','UNKNOWN')),
    outcome                text CHECK (outcome IN ('OK','TIMEOUT','RATE_LIMITED_TRANSIENT',
                               'QUOTA_TEMPORARY','QUOTA_EXHAUSTED','BILLING_OR_ACTIVATION',
                               'AUTH_DENIED','REQUEST_INVALID','SERVER_ERROR','NETWORK_ERROR',
                               'PROTOCOL_ERROR','UNKNOWN_ERROR')),  -- UNKNOWN_ERROR：v1.4 留痕补齐（§4.1 原文漏列，fail-closed 分类需要）
    http_status            int  CHECK (http_status BETWEEN 100 AND 599),
    retry_after_ms         bigint CHECK (retry_after_ms >= 0),
    prompt_tokens          int  not null default 0 CHECK (prompt_tokens >= 0),
    completion_tokens      int  not null default 0 CHECK (completion_tokens >= 0),
    total_tokens           int  not null default 0 CHECK (total_tokens >= 0),
    usage_missing          boolean not null default false,
    latency_ms             bigint CHECK (latency_ms >= 0),
    cost_micros            bigint CHECK (cost_micros >= 0),
    pricing_version        text,
    currency               text,
    input_price_micros_per_1k  bigint,
    output_price_micros_per_1k bigint,
    error_code             text,
    error_fingerprint      text,
    sanitized_message      text,
    started_at             timestamptz not null default now(),
    finished_at            timestamptz,

    -- 状态机一致性
    CHECK ((state = 'STARTED'   AND outcome IS NULL AND finished_at IS NULL)
        OR (state = 'SUCCEEDED' AND outcome = 'OK'  AND finished_at IS NOT NULL)
        OR (state = 'FAILED'    AND outcome IS NOT NULL AND outcome <> 'OK' AND finished_at IS NOT NULL)
        OR (state = 'UNKNOWN'   AND outcome IS NULL AND finished_at IS NOT NULL)),

    -- lineage 一致性
    CHECK ((route_role = 'PRIMARY'  AND fallback_from IS NULL)
        OR (route_role = 'FALLBACK' AND fallback_from IS NOT NULL)),

    -- usage 缺失时三计数必须全 0
    CHECK (usage_missing = false OR (prompt_tokens = 0 AND completion_tokens = 0 AND total_tokens = 0)),

    -- 成本非空则价格快照必须完整
    CHECK (cost_micros IS NULL OR (pricing_version IS NOT NULL AND currency IS NOT NULL
                                   AND input_price_micros_per_1k IS NOT NULL
                                   AND output_price_micros_per_1k IS NOT NULL)),

    UNIQUE (invocation_id, call_seq)
);

CREATE INDEX idx_model_call_ledger_run        ON model_call_ledger (review_run_id, started_at);
CREATE INDEX idx_model_call_ledger_route      ON model_call_ledger (route_id, started_at);
CREATE INDEX idx_model_call_ledger_invocation ON model_call_ledger (invocation_id, call_seq);
CREATE INDEX idx_model_call_ledger_started    ON model_call_ledger (started_at) WHERE state = 'STARTED';

-- 权限矩阵（§4.1）
GRANT INSERT, SELECT ON model_call_ledger TO control_app;

-- 列级 UPDATE：仅允许终态转换相关列
GRANT UPDATE (
    state, outcome, http_status, retry_after_ms,
    prompt_tokens, completion_tokens, total_tokens, usage_missing,
    latency_ms, cost_micros, pricing_version, currency,
    input_price_micros_per_1k, output_price_micros_per_1k,
    reported_model, provider_request_id,
    error_code, error_fingerprint, sanitized_message,
    finished_at
) ON model_call_ledger TO control_app;

-- publisher_app 零权限（与模型无关）；PUBLIC 零权限（已由全局策略拒绝）
revoke all on model_call_ledger from publisher_app;   -- 显式冻结，防未来 grant all 漂移（V2 惯例）
revoke all on model_call_ledger from public;
