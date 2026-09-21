#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---rca_report cols---'
$PG "select column_name from information_schema.columns where table_name='rca_report' order by ordinal_position;" | tr '\n' ' '
echo ''
echo '---tool/model call counts for run---'
$PG "select 'tool_calls='||count(*) from rca_tool_call where run_id='e19efae5-379e-4ba6-94b2-86f618312b21';" 2>&1
$PG "select 'model_calls='||count(*) from rca_model_call where run_id='e19efae5-379e-4ba6-94b2-86f618312b21';" 2>&1
$PG "select 'attempts='||count(*)||' states='||string_agg(state,',') from rca_attempt where run_id='e19efae5-379e-4ba6-94b2-86f618312b21';" 2>&1
