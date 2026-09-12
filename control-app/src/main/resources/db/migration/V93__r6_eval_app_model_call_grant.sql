-- R6/EV-06 评测费用链：UsageLedgerService 改读 rca_model_call 逐物理调用行
-- （身份链 eval_case_result.rca_run_id 显式映射，替换 usage_json 聚合读面）——
-- eval 批跑 profile 的 DB 身份是 eval_app，V48 只授了 control_app 写面。
-- 本迁移补 eval_app 只读授权（V11/V45 只读同律：零写开口，INSERT/UPDATE/DELETE
-- 仍只属 control_app）；rca_working_memory/rca_context_summary 不授（记忆/压缩
-- 面不进评测域，V91/V92 头注同律）。

grant select on rca_model_call to eval_app;
