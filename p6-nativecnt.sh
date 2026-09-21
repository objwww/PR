#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
RUN=6493f282
RID=$($PG "select rca_run_id from eval_case_result where eval_run_id::text like '$RUN%' and scenario_id='S4' limit 1;")
echo "RID=$RID"
echo '---rca_tool_invocation counts---'
$PG "select count(*) as total, count(distinct tool_name) as unique_tools from rca_tool_invocation where run_id='$RID';"
echo '---rca_evidence counts---'
$PG "select count(*) as total, count(distinct evidence_type) as unique_types from rca_evidence where run_id='$RID';"
echo '---eval_case_result current values---'
$PG "select tool_calls_total, tool_calls_unique, checkpoints_total, checkpoints_covered, conclusion_grounded from eval_case_result where eval_run_id::text like '$RUN%' and scenario_id='S4';"
