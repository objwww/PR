#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
RUN=6493f282
echo '---S4 rca_run id---'
RID=$($PG "select rca_run_id from eval_case_result where eval_run_id::text like '$RUN%' and scenario_id='S4' and verdict='DECIDABLE' limit 1;")
echo "RID=$RID"
echo '---rca_tool_invocation for this run---'
$PG "select count(*)||' rows' from rca_tool_invocation where run_id='$RID';" 2>&1
echo '---rca_evidence for this run---'
$PG "select count(*)||' rows' from rca_evidence where run_id='$RID';" 2>&1
echo '---rca_evidence types---'
$PG "select evidence_type||' x'||count(*) from rca_evidence where run_id='$RID' group by evidence_type;" 2>&1
echo '---rca_tool_invocation cols---'
$PG "select column_name from information_schema.columns where table_name='rca_tool_invocation' order by ordinal_position;" | tr '\n' ' '
echo ''
echo '---rca_tool_invocation sample---'
$PG "select tool_name||' | '||status||' | '||coalesce(left(result_summary,80),'-') from rca_tool_invocation where run_id='$RID' order by started_at limit 8;" 2>&1
