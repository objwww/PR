-- ============================================================================
-- V14 —— M-a：F14 注入点的履约行 DELETE 授权
--
-- F14（事件丢失/履约缺口）的故障点 dropFulfillmentTx 需要 arena_app 对
-- oa_fulfillment_order 的 DELETE 权——V13 的恢复面授权清单漏了本表。
-- 症状（T9 S21 演练捕获）：25 单全部 CREATED+履约行在+零台账/零支付+
-- 幂等卡 PROCESSING——快照提交后 DELETE 42501 即 500，断点精确落在
-- dropFulfillmentTx。
-- ============================================================================

grant delete on arena.oa_fulfillment_order to arena_app;
