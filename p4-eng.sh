#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---engine distribution---'
$PG "select engine||' '||purpose||' n='||count(*) from rca_run group by engine, purpose order by engine, purpose;"
echo '---which investigations ever produced rca_tool_call rows---'
$PG "select count(distinct result_id) from rca_tool_call;" 2>&1
echo '---rca_tool_call table source: cols---'
$PG "select column_name from information_schema.columns where table_name='rca_tool_call' order by ordinal_position;" | tr '\n' ' '
echo ''
echo '---model_call heavy runs (LLM agents) recent---'
$PG "select run_id, count(*) as c from rca_model_call group by run_id order by c desc limit 3;"
