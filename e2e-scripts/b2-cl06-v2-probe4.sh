#!/bin/sh
# b2-cl06-v2-probe4.sh —— v5.1 步序/记忆行触发取证
. /opt/build/r7-operator-env.sh
. /opt/build/b2tree/e2e-r7-common.sh
G() { r7_psql_ro R7_PG_URL "$1" '-At'; }
echo "== rca_tool_invocation columns:"
G "select string_agg(column_name,',') from information_schema.columns where table_name='rca_tool_invocation'"
echo "== rca_delegation_receipt columns:"
G "select string_agg(column_name,',') from information_schema.columns where table_name='rca_delegation_receipt'"
echo "== invocations run1:"
G "select seq||' '||tool_name from rca_tool_invocation where run_id='bbf9af6a-8b1e-4fa3-80bc-d8672722c003' order by seq"
echo "== receipts run1:"
G "select gap_id||' '||child_status||' cref='||counter_refs||' miss='||missing_information from rca_delegation_receipt where run_id='bbf9af6a-8b1e-4fa3-80bc-d8672722c003'"
echo "== memory writer code:"
grep -rn 'rca_working_memory' /opt/build/pr/control-app/src/main/java --include=*.java -l
grep -rn 'insert into rca_working_memory\|INSERT INTO rca_working_memory' /opt/build/pr/control-app/src/main/java -il
