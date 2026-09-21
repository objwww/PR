#!/bin/sh
sleep 540
echo '--- run-7 状态/阶段 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select state from eval_run where id='f87b17a5-19b8-4159-9cd7-b492509f0d70';"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select phase||' @ '||to_char(entered_at,'HH24:MI:SS')||' || '||coalesce(detail::text,'-') from eval_phase_event where eval_run_id='f87b17a5-19b8-4159-9cd7-b492509f0d70' order by entered_at;"
echo '--- 案例行（三维列）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select scenario_id||' r'||round_no||' | '||verdict||' | cause='||coalesce(cause_component_hit::text,'-')||'/'||coalesce(cause_fault_hit::text,'-')||'/'||coalesce(cause_reason_hit::text,'-')||' | path='||coalesce(checkpoints_covered::text,'-')||'/'||coalesce(checkpoints_total::text,'-')||' | grounded='||coalesce(conclusion_grounded::text,'-')||' | tools='||coalesce(tool_calls_total::text,'-')||'/'||coalesce(tool_calls_unique::text,'-') from eval_case_result where eval_run_id='f87b17a5-19b8-4159-9cd7-b492509f0d70';"
