#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---eval_comparison table---'
$PG "select table_name from information_schema.tables where table_name like '%comparison%';"
echo '---existing comparison records---'
$PG "select left(candidate_run_id::text,8)||' vs '||left(baseline_run_id::text,8)||' | '||coalesce(gate_outcome,'-')||' | '||created_at from eval_comparison order by created_at desc limit 5;" 2>&1
echo '---compare controller api---'
