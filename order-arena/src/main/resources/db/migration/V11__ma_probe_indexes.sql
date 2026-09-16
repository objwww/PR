-- ============================================================================
-- V11 —— M-a：探测面索引补齐（真实性能事故修复）
--
-- T8 真栈演练捕获：oa_* 业务表经 8 天流量累积至百万行级（oa_resource_ledger 400 万），
-- DomainProbe 每轮的计数/违规查询全部并行顺序扫（单条 1.6~4.7s，一轮累计 ~4min）——
-- 探测节奏从 30s 退化到分钟级，业务告警在演练窗内永不 firing；生产面等价于
-- "探针半失明 + DB 持续被全表扫打磨"。按探测谓词逐条补索引。
-- ============================================================================

-- stuckOrders（CREATED 超龄）/ countOrdersCreatedSince（S23/S24 分母、S21 差值）
create index ix_trade_created_at on arena.oa_trade_order(created_at);

-- countOrdersSuccessSince（S23 分子）
create index ix_trade_enabled_at on arena.oa_trade_order(enabled_at);

-- duplicateOrders（F1 症状分组）
create index ix_trade_intent_id on arena.oa_trade_order(intent_id);

-- countPaymentsSuccessSince（S16 差值左项）/ duplicatePayments（S18 症状）
create index ix_pay_kind_result_settled on arena.oa_payment_record(kind, result, settled_at);

-- pendingPaymentOrders（S17 症状：AUTH INITIATED 超龄）
create index ix_pay_kind_result_initiated on arena.oa_payment_record(kind, result, initiated_at);

-- countFulfillmentsStartedSince（S21 差值右项）/ fulfillmentOverdue（S25 症状）
create index ix_fulfill_created_at on arena.oa_fulfillment_order(created_at);

-- inventoryOversell（S20 症状）/ reconDiff（S19 症状）的资源行过滤
create index ix_ledger_type_dir_order on arena.oa_resource_ledger(resource_type, direction, order_id);
