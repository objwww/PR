#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
RID=49f1d3b4-172d-4da1-8ce6-5dad3e183578
echo '---tool_invocation detail---'
$PG "select call_seq||'|'||tool_name||'|'||state||'|'||coalesce(reason_code,'-') from rca_tool_invocation where run_id='$RID' order by call_seq;"
echo '---evidence detail---'
$PG "select evidence_type||'|'||coalesce(left(source_ref,80),'-') from rca_evidence where run_id='$RID' order by id;"
echo '---rca_evidence cols---'
$PG "select column_name from information_schema.columns where table_name='rca_evidence' order by ordinal_position;" | tr '\n' ' '
echo ''
echo '---report summary field---'
$PG "select left(raw_text,300) from rca_report where run_id='$RID' limit 1;"
