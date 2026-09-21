#!/bin/sh
# p8 验证批轮询：run 状态 + 案例工具计数真值抽查
RUN=52f84916-58bf-4eb5-9f05-90cc4220eeec
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'run='||state from eval_run where id='$RUN'"
echo '--- 已评案例:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'case '||scenario_id||' r'||round_no||' '||verdict||' tool='||coalesce(tool_calls_total,-1)||' uniq='||coalesce(tool_calls_unique,-1)||' latency='||coalesce(latency_ms,-1) from eval_case_result where eval_run_id='$RUN' order by scenario_id, round_no"
echo '--- 汇总（tool=非零案例数/已评案例数）:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'cases='||count(*)||' tool_nonzero='||count(*) filter (where tool_calls_total>0)||' verdict_decidable='||count(*) filter (where verdict='DECIDABLE') from eval_case_result where eval_run_id='$RUN'"
