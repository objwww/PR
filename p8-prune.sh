#!/bin/sh
echo '--- prune 悬空（无 tag 无容器引用）:'
docker image prune -f 2>&1 | tail -1
df -h / | tail -1
echo '--- 最终 ledger 复核（两批对比）:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'batch1(grant前) cases='||count(*)||' tool_nonzero='||count(*) filter (where tool_calls_total>0) from eval_case_result where eval_run_id='52f84916-58bf-4eb5-9f05-90cc4220eeec'"
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'batch2(干净批) cases='||count(*)||' tool_nonzero='||count(*) filter (where tool_calls_total>0)||' decidable='||count(*) filter (where verdict='DECIDABLE')||' hit='||count(*) filter (where root_cause_hit) from eval_case_result where eval_run_id='5aae9a93-527a-4a83-aca1-ddcee0a65b4a'"
