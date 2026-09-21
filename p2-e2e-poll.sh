#!/bin/sh
sleep 90
echo '--- worker 日志（关键行）---'
grep -E 'REPLAYING|AWAITING|SCORING|FINALIZING|回放|replay|claim|LAUNCH|WARN|ERROR' /tmp/p2-worker.log | tail -14
echo '--- run 状态 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select state||' | '||coalesce(terminal_reason,'-') from eval_run where id='eed0e10d-e020-4f3c-abbc-161cbc166d1a';"
echo '--- 事故状态（是否重投 firing）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select status||' | gen='||generation from incident where id='f4cf44b2-a5fd-48fe-875c-69ba3f45d78a';"
echo '--- 新 rca_run ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select id||' | '||state||' | '||trigger_kind||' | created='||created_at from rca_run where incident_id='f4cf44b2-a5fd-48fe-875c-69ba3f45d78a' order by created_at desc limit 2;"
echo '--- eval_case_result ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select scenario_id||' r'||round_no||' | '||verdict||' | hit='||root_cause_hit||' | run='||coalesce(rca_run_id::text,'-') from eval_case_result where eval_run_id='eed0e10d-e020-4f3c-abbc-161cbc166d1a';"
