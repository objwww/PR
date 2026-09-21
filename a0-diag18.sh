#!/bin/sh
. /opt/build/r7-operator-env.sh
RUN_ID=$($R7_PSQL_CMD "$R7_PG_URL" -tA -c "select id from rca_run where created_at > now() - interval '8 minutes' order by created_at desc limit 1;")
echo "run=$RUN_ID"
echo '=== 证据（类型/来源） ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select evidence_type, source from rca_evidence where run_id='$RUN_ID';"
echo '=== 报告头 ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select left(package_json::text, 320) from rca_report where run_id='$RUN_ID';"
echo ''
echo '=== WARN 尾 ==='
docker logs deploy-control-app-1 --since 7m 2>&1 | grep WARN | grep -v UserDetails | tail -5
exit 0
