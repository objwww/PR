#!/bin/sh
# A0 phase5 FAIL 诊断：模型调用/工具调用/报告面计数（只读）
. /opt/build/r7-operator-env.sh
RUN_ID=$($R7_PSQL_CMD "$R7_PG_URL" -tA -c "select id from rca_run where created_at > now() - interval '20 minutes' order by created_at desc limit 1;")
echo "run=$RUN_ID"
echo "=== 模型调用（role/model/tokens） ==="
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select role_id, model, total_tokens from rca_model_call where run_id='$RUN_ID' order by action_seq;"
echo "=== 工具调用账行 ==="
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select count(*) from rca_tool_invocation where run_id='$RUN_ID';"
echo "=== 证据行 ==="
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select count(*) from rca_evidence where run_id='$RUN_ID';"
echo "=== 报告 evidence_refs ==="
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select jsonb_array_elements_text(package_json->'evidence'->'evidence_refs') from rca_report where run_id='$RUN_ID' limit 5;"
echo "=== 模型名（非密钥） ==="
grep -E '^AGENT_MODEL=' /opt/build/pr/deploy/.env
exit 0
