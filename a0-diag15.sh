#!/bin/sh
. /opt/build/r7-operator-env.sh
RUN_ID=7aa52a22-1215-4e3f-acde-40cc1767d11c
echo '=== 证据行 ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select count(*), count(distinct evidence_type) from rca_evidence where run_id='$RUN_ID';"
echo '=== 证据类型分布 ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select evidence_type, count(*) from rca_evidence where run_id='$RUN_ID' group by 1;"
echo '=== 检查点 final_claims（最后一轮） ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select final_claims from primary_checkpoint where run_id='$RUN_ID' order by created_at desc limit 1;" 2>/dev/null | head -c 800
echo ''
echo '=== checkpoint 表名探测 ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select table_name from information_schema.tables where table_name like '%checkpoint%';"
echo '=== 报告 analysis 摘要 ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select left(package_json->'analysis',400) from rca_report where run_id='$RUN_ID';"
exit 0
