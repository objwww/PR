-- ============================================================================
-- V8 —— M-a 业务扩编：履约消费尝试表（F15 重复消费的探测面）
--   at-least-once 语义下正常恰一次尝试；F15（ack 失败）产生重复尝试行——
--   DomainProbe 以 attempt>1/履约单 检出重复消费（oa_duplicate_fulfillments_current）。
--   表级授权随建表同批授予（V134 教训落实；arena 域同律）。
-- ============================================================================

create table arena.oa_fulfillment_attempt (
    id             uuid primary key,
    fulfillment_id uuid not null references arena.oa_fulfillment_order (id),
    consumer       text not null,
    created_at     timestamptz not null default now()
);

create index ix_ff_attempt_ff on arena.oa_fulfillment_attempt (fulfillment_id);

grant select, insert, update on arena.oa_fulfillment_attempt to arena_app;
grant select on arena.oa_fulfillment_attempt to eval_app;
