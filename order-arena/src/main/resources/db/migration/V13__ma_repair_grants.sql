-- ============================================================================
-- V13 —— M-a：恢复面 DELETE 授权
--
-- 业务族恢复的注入工件清除（F11 删多余 capture / F13 删超额扣减行 / F15 删重复
-- attempt 行）需要 arena_app 对三张表的 DELETE 权——既有授权只有 select/insert/
-- update，DELETE 报 42501 被 Spring 映射成 bad SQL grammar（T8 S18 演练捕获）。
-- ============================================================================

grant delete on arena.oa_payment_record to arena_app;
grant delete on arena.oa_resource_ledger to arena_app;
grant delete on arena.oa_fulfillment_attempt to arena_app;
