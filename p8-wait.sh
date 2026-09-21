#!/bin/sh
# 等待循环：每 60s 查一次，直到 run 出终态或 8 分钟窗口用尽
RUN=52f84916-58bf-4eb5-9f05-90cc4220eeec
i=0
while [ $i -lt 8 ]; do
  S=$(docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
    "select state from eval_run where id='$RUN'")
  C=$(docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
    "select count(*) from eval_case_result where eval_run_id='$RUN'")
  echo "[$i] run=$S cases=$C $(date +%H:%M:%S)"
  case "$S" in COMPLETED|FAILED|CANCELLED) break ;; esac
  sleep 60
  i=$((i+1))
done
echo '--- 案例明细:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'case '||scenario_id||' r'||round_no||' '||verdict||' tool='||coalesce(tool_calls_total,-1)||' uniq='||coalesce(tool_calls_unique,-1)||' lat='||coalesce(latency_ms,-1) from eval_case_result where eval_run_id='$RUN' order by created_at limit 30"
