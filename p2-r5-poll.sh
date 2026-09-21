#!/bin/sh
sleep 240
echo '--- run-5 状态/阶段 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select state from eval_run where id='81c69fa3-e971-4531-a652-69e2b792c2e7';"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select phase||' @ '||to_char(entered_at,'HH24:MI:SS')||' || '||coalesce(detail::text,'-') from eval_phase_event where eval_run_id='81c69fa3-e971-4531-a652-69e2b792c2e7' order by entered_at;"
echo '--- 事故/新调查 run ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select status||' | gen='||generation from incident where id='f4cf44b2-a5fd-48fe-875c-69ba3f45d78a';"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(id::text,8)||' | '||state||' | '||trigger_kind||' | '||created_at from rca_run where incident_id='f4cf44b2-a5fd-48fe-875c-69ba3f45d78a' order by created_at desc limit 2;"
echo '--- 案例行 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select scenario_id||' r'||round_no||' | '||verdict||' | hit='||root_cause_hit from eval_case_result where eval_run_id='81c69fa3-e971-4531-a652-69e2b792c2e7';"
