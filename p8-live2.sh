#!/bin/sh
RUN=52f84916-58bf-4eb5-9f05-90cc4220eeec
echo '--- eval 专属日志(15m):'
docker logs --since 15m eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -iE 'EvalRun|EvalBatch|rca|评测|锚点|anchor|scenario' | grep -v drill | tail -10
echo '--- 该批 rca_run 侧状态:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select r.state||' '||coalesce(r.trigger_type,'-')||' started='||to_char(r.started_at,'HH24:MI:SS') from rca_run r where r.id in (select rca_run_id from eval_case_result where eval_run_id='$RUN' and rca_run_id is not null) or exists (select 1 from eval_case_input i where i.eval_run_id='$RUN' and i.rca_run_id=r.id) order by r.started_at desc limit 8" 2>/dev/null
echo '--- eval_case_input 已备未评:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select scenario_id||' r'||round_no||' '||state||' run='||coalesce(rca_run_id::text,'-') from eval_case_input where eval_run_id='$RUN' order by created_at desc limit 6" 2>/dev/null
