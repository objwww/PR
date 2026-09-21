#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---my rejection reason---'
sed 's/\x1b\[[0-9;]*m//g' /tmp/p6-worker.log | grep '拒绝 LAUNCH' | tail -1 | cut -c1-400
echo '---their x30 run verdicts---'
$PG "select verdict, count(*) from eval_case_result where eval_run_id='21de9287-4c36-4663-ba64-4cdcf55e59b5' group by verdict;"
echo '---their judge rows---'
$PG "select scenario_id||'|'||verdict||'|'||passed||'/'||total||'|'||rubric_version||'|'||model from eval_case_judge where eval_run_id='21de9287-4c36-4663-ba64-4cdcf55e59b5' limit 8;"
echo '---their dims sample---'
$PG "select scenario_id||'|r'||round_no||'|hit='||root_cause_hit||'|comp='||coalesce(cause_component_hit::text,'-')||'|ckpt='||coalesce(checkpoints_covered::text,'-')||'/'||coalesce(checkpoints_total::text,'-')||'|grnd='||coalesce(conclusion_grounded::text,'-')||'|tools='||coalesce(tool_calls_total::text,'-')||'|lat='||coalesce(latency_ms::text,'-') from eval_case_result where eval_run_id='21de9287-4c36-4663-ba64-4cdcf55e59b5' order by scenario_id, round_no limit 8;"
echo '---quality gate/usage---'
$PG "select quality_verdict, usage_status from eval_run where id='21de9287-4c36-4663-ba64-4cdcf55e59b5';"
