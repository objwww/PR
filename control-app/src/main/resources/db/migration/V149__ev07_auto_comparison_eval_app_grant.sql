-- ============================================================================
-- V149 —— EV-07 自动落档授权扩展：eval_app 获得 eval_comparison select,insert
--   背景：eval run 终态钩子（EvalBatchRunner.RunLifecycle →
--   EvalComparisonAutoRecorder）在 worker 侧（eval_app 身份）自动落档
--   baseline×candidate 对比结论；V85 原授权面只放 control_app（读面手工落档），
--   eval_app 显式 revoke。自动落档使 eval-runner 成为该表的生产者之一，
--   授权面据此扩展：insert-only 语义不变（零 update/delete 开口），
--   其余角色（publisher/notify/arena/chaos/public）维持 V85 归零。
-- ============================================================================

do $$
begin
    if exists (select from pg_roles where rolname = 'eval_app') then
        grant select, insert on eval_comparison to eval_app;
    end if;
end
$$;
