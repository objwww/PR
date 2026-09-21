#!/bin/sh
. /opt/build/r7-operator-env.sh
RUN_ID=$($R7_PSQL_CMD "$R7_PG_URL" -tA -c "select id from rca_run where created_at > now() - interval '12 minutes' order by created_at desc limit 1;")
echo "run=$RUN_ID"
echo '=== 账行状态 ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select call_seq, state, result_ref is not null as has_ref from rca_tool_invocation where run_id='$RUN_ID' order by call_seq;"
echo '=== 本 run 应用日志（WARN/ERROR 尾 12） ==='
docker logs deploy-control-app-1 --since 12m 2>&1 | grep -E "WARN|ERROR" | tail -12
echo '=== 证据/报告 ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select count(*) from rca_evidence where run_id='$RUN_ID';"
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select validation_status, left(raw_text,150) from rca_report where run_id='$RUN_ID';"
exit 0
