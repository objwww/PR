#!/bin/sh
RUN2=61b9dc4f-d023-4f5d-a768-ad80e5afb3d4
echo '--- B2 进度:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'b2='||(select state from eval_run where id='$RUN2')||' cases='||count(*) from eval_case_result where eval_run_id='$RUN2'"
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select coalesce(string_agg(scenario_id||'/'||verdict, ', '),'(未评)') from eval_case_result where eval_run_id='$RUN2'"
echo '--- 对比较表列名:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select string_agg(column_name,',') from information_schema.columns where table_name='eval_comparison'"
echo '--- 链日志尾:'
tail -3 /tmp/p12c-chain.log 2>/dev/null
