-- ============================================================================
-- V11 —— AM3 eval-runner 只读授权（M3-15；docs/告警AM3-落码技术方案.md v1.1）
--
-- eval-runner（eval_app 独立身份，M3-15）单案例评分的数据源面：
--   rca_run          轮询评测 run 终态（SUCCEEDED/FAILED/CANCELLED）+ 时延起点
--   rca_attempt      评分对象选择（final-validated-report-v1：终局 attempt 绑定）
--   rca_report       报告读取（selection_policy + package_json 评分输入）
--   incident         flagd 场景（S1/S2 无靶场 scenario_map）的 run 归属匹配
--                    （incident_key 的 alertname 面 + created_at > 激活时刻）
-- V9 已授 rca_investigation_result / rca_tool_call（attempt→result→tool_calls 链）。
--
-- 纪律：
--   - 只读（select）——eval_app 不改生产调查链路任何一行；
--   - GT 延迟授权不受影响：arena.eval_release_gt 的三条件门禁照旧
--     （本迁移不触碰 ground_truth_scenario / oa_scenario_map）；
--   - control/publisher/notify/arena 域角色照旧零变化（本迁移不新增 revoke 目标）。
-- ============================================================================

grant select on rca_run to eval_app;
grant select on rca_attempt to eval_app;
grant select on rca_report to eval_app;
grant select on incident to eval_app;
