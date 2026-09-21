#!/bin/sh
# 批次终态观察器：nohup 后台跑，终态后把对账报告写 /tmp/p8-batch1-final.txt
RUN=52f84916-58bf-4eb5-9f05-90cc4220eeec
OUT=/tmp/p8-batch1-final.txt
: > "$OUT"
while true; do
  S=$(docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
    "select state from eval_run where id='$RUN'")
  case "$S" in COMPLETED|FAILED|CANCELLED) break ;; esac
  sleep 60
done
{
  echo "run=$S at $(date '+%F %T')"
  echo '=== 案例明细 ==='
  docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
    "select scenario_id as sc, verdict, tool_calls_total as tool, tool_calls_unique as uniq,
            root_cause_hit as hit, latency_ms
       from eval_case_result where eval_run_id='$RUN' order by scenario_id, round_no"
  echo '=== 汇总 ==='
  docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
    "select 'cases='||count(*)||' tool_nonzero='||count(*) filter (where tool_calls_total>0)||
            ' decidable='||count(*) filter (where verdict='DECIDABLE')||
            ' hit='||count(*) filter (where root_cause_hit)||
            ' latency_p50='||coalesce(percentile_cont(0.5) within group (order by latency_ms),0)::int
       from eval_case_result where eval_run_id='$RUN'"
} >> "$OUT" 2>&1
echo DONE >> "$OUT"
