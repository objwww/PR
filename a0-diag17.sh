#!/bin/sh
. /opt/build/r7-operator-env.sh
RUN_ID=$1
echo "run=$RUN_ID"
echo '=== 证据行（类型分布） ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select evidence_type, count(*) from rca_evidence where run_id='$RUN_ID' group by 1;"
echo '=== 检查点 final_claims（最新） ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select final_claims from rca_primary_checkpoint where run_id='$RUN_ID' order by created_at desc limit 1;" | head -c 1500
echo ''
echo '=== 报告 analysis 头 ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select left(package_json::text, 400) from rca_report where run_id='$RUN_ID';"
echo '=== runner 侧 WARN（该 run 窗口） ==='
docker logs deploy-control-app-1 --since 8m 2>&1 | grep -E 'WARN' | grep -v UserDetails | tail -8
exit 0
