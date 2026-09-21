#!/bin/sh
echo '--- arenaorderstuck 键的路由决策（正确大小写）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select decision||' | '||percent||'%% | '||created_at from canary_route_decision where stickiness_key like 'alertname=arenaorderstuck%' order by created_at desc limit 6;"
echo '--- run-7 当前进度 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select state from eval_run where id='f87b17a5-19b8-4159-9cd7-b492509f0d70';"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select scenario_id||' r'||round_no||' | '||verdict||' | cause='||coalesce(cause_component_hit::text,'-')||'/'||coalesce(cause_fault_hit::text,'-')||'/'||coalesce(cause_reason_hit::text,'-')||' | path='||coalesce(checkpoints_covered::text,'-')||'/'||coalesce(checkpoints_total::text,'-')||' | grounded='||coalesce(conclusion_grounded::text,'-')||' | tools='||coalesce(tool_calls_total::text,'-')||'/'||coalesce(tool_calls_unique::text,'-') from eval_case_result where eval_run_id='f87b17a5-19b8-4159-9cd7-b492509f0d70';"
