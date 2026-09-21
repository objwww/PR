#!/bin/sh
# p8c 干净批观察器
RUN=5aae9a93-527a-4a83-aca1-ddcee0a65b4a
OUT=/tmp/p8c-final.txt
: > "$OUT"
while true; do
  S=$(docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
    "select state from eval_run where id='$RUN'")
  case "$S" in COMPLETED|SUCCEEDED|FAILED|CANCELLED) break ;; esac
  sleep 45
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
            ' hit='||count(*) filter (where root_cause_hit)
       from eval_case_result where eval_run_id='$RUN'"
  echo '=== worker warn 日志（回退读失败应无）==='
  docker logs eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep '回退读失败' | tail -3
  echo '=== V157 flyway 台账 ==='
  docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
    "select version||' '||success from flyway_schema_history where version in ('153','157') order by installed_rank"
} >> "$OUT" 2>&1
echo DONE >> "$OUT"
