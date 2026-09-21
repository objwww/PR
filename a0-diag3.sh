#!/bin/sh
# A0 诊断三：run 生命周期日志面（WARN/ERROR/证据生产）
. /opt/build/r7-operator-env.sh
RUN_ID=$($R7_PSQL_CMD "$R7_PG_URL" -tA -c "select id from rca_run where created_at > now() - interval '30 minutes' order by created_at desc limit 1;")
echo "run=$RUN_ID"
docker logs deploy-control-app-1 --since 40m 2>&1 | grep -iE "warn|error" | grep -v "^$" | tail -20
echo '=== 模型调用与费用 ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select count(*) from rca_model_call where run_id='$RUN_ID';"
echo '=== run last_error ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select coalesce(last_error,'<null>') from rca_run where id='$RUN_ID';"
echo '=== 检查点 final_claims ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select jsonb_array_length(final_claims) from primary_checkpoint where run_id='$RUN_ID' order by created_at desc limit 1;"
exit 0
