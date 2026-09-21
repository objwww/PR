#!/bin/sh
RUN=5aae9a93-527a-4a83-aca1-ddcee0a65b4a
cat /tmp/p8c-final.txt 2>/dev/null
echo '--- 即时状态:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'run='||state from eval_run where id='$RUN'"
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'case '||scenario_id||' '||verdict||' tool='||coalesce(tool_calls_total,-1)||' uniq='||coalesce(tool_calls_unique,-1) from eval_case_result where eval_run_id='$RUN' order by scenario_id"
