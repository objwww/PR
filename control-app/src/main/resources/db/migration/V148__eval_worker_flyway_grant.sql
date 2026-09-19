-- 陈旧 eval worker 自拒护栏（WorkerSchemaFreshnessGuard，2026-09-17）：
-- eval runner 以 eval_app 身份对照 DB flyway 最大版本与本进程迁移面，需读面授权
-- （control_app 同授权先例 V127）
GRANT SELECT ON flyway_schema_history TO eval_app;
