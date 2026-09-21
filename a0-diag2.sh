#!/bin/sh
# A0 诊断二：账行状态 + 模型调用列名自适应
. /opt/build/r7-operator-env.sh
RUN_ID=$($R7_PSQL_CMD "$R7_PG_URL" -tA -c "select id from rca_run where created_at > now() - interval '30 minutes' order by created_at desc limit 1;")
echo "run=$RUN_ID"
echo "=== 工具账行状态/序号 ==="
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select call_seq, state, result_ref is not null as has_ref from rca_tool_invocation where run_id='$RUN_ID' order by call_seq;"
echo "=== rca_model_call 列 ==="
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select column_name from information_schema.columns where table_name='rca_model_call' and column_name in ('role_id','model_name','total_tokens','tokens_total','finish_reason') ;"
echo "=== 模型调用计数 ==="
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select count(*), coalesce(sum(total_tokens),0) from rca_model_call where run_id='$RUN_ID';"
echo "=== 检查点 steps/状态 ==="
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select state, steps_used from rca_task where run_id='$RUN_ID';"
echo "=== 报告 validation ==="
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select validation_status, left(raw_text,120) from rca_report where run_id='$RUN_ID';"
exit 0
