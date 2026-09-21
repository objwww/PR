#!/bin/sh
sleep 280
echo '--- run-6 状态/阶段 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select state from eval_run where id='daf6f7bd-41b5-484a-aa38-f0608cbc855f';"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select phase||' @ '||to_char(entered_at,'HH24:MI:SS')||' || '||coalesce(detail::text,'-') from eval_phase_event where eval_run_id='daf6f7bd-41b5-484a-aa38-f0608cbc855f' order by entered_at;"
echo '--- 案例行（含三维列）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select scenario_id||' r'||round_no||' | '||verdict||' | cause='||coalesce(cause_component_hit::text,'-')||'/'||coalesce(cause_fault_hit::text,'-')||'/'||coalesce(cause_reason_hit::text,'-')||' | path='||coalesce(checkpoints_covered::text,'-')||'/'||coalesce(checkpoints_total::text,'-')||' | grounded='||coalesce(conclusion_grounded::text,'-')||' | tools='||coalesce(tool_calls_total::text,'-')||'/'||coalesce(tool_calls_unique::text,'-') from eval_case_result where eval_run_id='daf6f7bd-41b5-484a-aa38-f0608cbc855f';"
