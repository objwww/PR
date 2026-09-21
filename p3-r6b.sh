#!/bin/sh
sleep 300
echo '--- 22:41 后的新调查 run ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(r.id::text,8)||' | '||r.state||' | '||r.created_at||' | err='||coalesce(r.last_error::text,'-') from rca_run r where r.created_at > '2026-09-16 22:41:00+00' order by r.created_at desc limit 3;"
echo '--- 事故状态 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select status||' | gen='||generation||' | updated='||updated_at from incident where incident_key like 'alertname=ArenaOrderStuck%' order by episode_started_at desc limit 1;"
echo '--- run-6 状态 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select state from eval_run where id='daf6f7bd-41b5-484a-aa38-f0608cbc855f';"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select scenario_id||' r'||round_no||' | '||verdict||' | cause='||coalesce(cause_component_hit::text,'-')||'/'||coalesce(cause_fault_hit::text,'-')||'/'||coalesce(cause_reason_hit::text,'-')||' | path='||coalesce(checkpoints_covered::text,'-')||'/'||coalesce(checkpoints_total::text,'-')||' | grounded='||coalesce(conclusion_grounded::text,'-')||' | tools='||coalesce(tool_calls_total::text,'-')||'/'||coalesce(tool_calls_unique::text,'-') from eval_case_result where eval_run_id='daf6f7bd-41b5-484a-aa38-f0608cbc855f';"
