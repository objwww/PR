#!/bin/bash
# M-d T9 §二 端点真机验证 v2（operator bearer 鉴权；密钥零回显）
set -u
KEY=$(docker exec deploy-control-app-1 sh -c 'env | grep -E "^APP_OPERATOR_API_BEARER=" | head -1 | cut -d= -f2-')
[ -z "$KEY" ] && { echo "no-operator-bearer-in-env"; exit 0; }
AH="Authorization: Bearer $KEY"
RID=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select id from eval_run where state='SUCCEEDED' order by started_at desc limit 1")
echo "run=$RID"
echo "six-parts: $(curl -s -H "$AH" "http://127.0.0.1:8080/api/eval/runs/$RID/six-parts" | head -c 260)"
echo "process-metrics: $(curl -s -H "$AH" "http://127.0.0.1:8080/api/eval/runs/$RID/process-metrics" | head -c 380)"
echo "approval-chain: $(curl -s -H "$AH" "http://127.0.0.1:8080/api/eval/runs/$RID/approval-chain" | head -c 220)"
RRID=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select rca_run_id from eval_case_result where eval_run_id='$RID' and rca_run_id is not null limit 1")
echo "rca_run=$RRID"
echo "trace-details: $(curl -s -H "$AH" "http://127.0.0.1:8080/api/rca-runs/$RRID/trace-details" | head -c 380)"
echo "drafts: $(curl -s -H "$AH" "http://127.0.0.1:8080/api/v1/prompt-workbench/drafts" | head -c 140)"
