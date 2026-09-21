#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---all comparisons---'
$PG "select left(baseline_run_id::text,8)||' vs '||left(candidate_run_id::text,8)||' | outcome='||coalesce(gate_outcome,'-')||'|'||created_at::date from eval_comparison order by created_at limit 12;"
echo '---columns---'
$PG "select column_name from information_schema.columns where table_name='eval_comparison' order by ordinal_position;" | tr '\n' ' '
