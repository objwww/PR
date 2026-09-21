#!/bin/sh
RUN=348e9ba5-2926-4114-b279-6066209f206a
echo '--- 监视器（全部）:'
cat /tmp/p12a-watch.txt 2>/dev/null
echo '--- 批状态:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'run='||state from eval_run where id='$RUN'"
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'case '||scenario_id||' '||verdict||' tool='||coalesce(tool_calls_total,-1)||' hit='||root_cause_hit from eval_case_result where eval_run_id='$RUN' order by scenario_id"
echo '--- order-arena 清偿日志（40m）:'
docker logs --since 40m alert-order-arena-1 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '窗口内清偿|恢复收口' | tail -6
