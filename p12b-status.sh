#!/bin/sh
echo '--- 链日志:'
cat /tmp/p12b-chain.log 2>/dev/null
echo '--- B1 进度:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'b1='||(select state from eval_run where id='91aa342a-0332-4df4-ae7f-7ad9e05118ca')||' cases='||count(*) from eval_case_result where eval_run_id='91aa342a-0332-4df4-ae7f-7ad9e05118ca'"
echo '--- B1p 进度:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'b1p='||(select state from eval_run where id='f6b8248f-d60e-46a5-922a-9fbe500993a6')||' cases='||count(*) from eval_case_result where eval_run_id='f6b8248f-d60e-46a5-922a-9fbe500993a6'"
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select coalesce(string_agg(scenario_id||'/'||verdict, ', '),'(未评)') from eval_case_result where eval_run_id='f6b8248f-d60e-46a5-922a-9fbe500993a6'"
echo '--- B2 是否已发:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select coalesce(string_agg(t.line, E'\n'),'(未发)') from (select display_name||' '||state as line from eval_run where display_name like 'P12%' order by created_at) t"
echo '--- 在飞会话:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select coalesce(string_agg(state||':'||scenario_id, ', '),'(无)') from arena.oa_chaos_session where state in ('ACTIVE','RECOVERING')"
