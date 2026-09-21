#!/bin/sh
# 等 run-8 的第二个案例（replay-p3）两轮收官（每轮 17 分钟预算）
sleep 540
echo '--- run-8 阶段 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select state from eval_run where id='f0b7fe31-2225-4f38-9cd2-de611e09da78';"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select phase||' @ '||to_char(entered_at,'HH24:MI:SS')||' || '||coalesce(detail::text,'-') from eval_phase_event where eval_run_id='f0b7fe31-2225-4f38-9cd2-de611e09da78' order by entered_at;"
echo '--- 案例行 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select scenario_id||' r'||round_no||' | '||verdict||' | cause='||coalesce(cause_component_hit::text,'-')||'/'||coalesce(cause_fault_hit::text,'-')||'/'||coalesce(cause_reason_hit::text,'-')||' | path='||coalesce(checkpoints_covered::text,'-')||'/'||coalesce(checkpoints_total::text,'-')||' | grounded='||coalesce(conclusion_grounded::text,'-')||' | tools='||coalesce(tool_calls_total::text,'-')||'/'||coalesce(tool_calls_unique::text,'-') from eval_case_result where eval_run_id='f0b7fe31-2225-4f38-9cd2-de611e09da78';"
echo '--- 有无 22:19 后新调查 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from rca_run where incident_id=(select id from incident where incident_key like 'alertname=ArenaOrderStuck%' order by episode_started_at desc limit 1) and created_at > '2026-09-16 22:20:00+00';"
