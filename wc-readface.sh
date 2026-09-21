#!/bin/sh
# WC-5 读面真机核验：GET /api/rca-runs/{id} 详情头四字段（operator bearer，token 不回显）
cd /opt/build/pr/deploy
TOK=$(grep -E '^APP_OPERATOR_API_BEARER=' .env | head -1 | cut -d= -f2- | tr -d '"')
RUN_ID=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c \
  "select id from rca_run where state='CANCELLED' order by updated_at desc limit 1")
RUN_ID2=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c \
  "select id from rca_run where state='RUNNING' order by updated_at desc limit 1")
echo "cancelled_run=$RUN_ID running_run=$RUN_ID2"
for id in "$RUN_ID" "$RUN_ID2"; do
  echo "--- detail head of $id ---"
  curl -s -H "Authorization: Bearer $TOK" "http://127.0.0.1:8080/api/rca-runs/$id" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); h=d.get('run',{}); print({k: h.get(k) for k in ('status','terminationRequestedAt','localExecutionState','inflightCount','unknownActionCount')})" 2>/dev/null \
    || curl -s -H "Authorization: Bearer $TOK" "http://127.0.0.1:8080/api/rca-runs/$id" | head -c 400
done
echo '--- reconciler 活性（近 10 分钟决策/扫描日志计数）---'
docker logs deploy-control-app-1 --since 10m 2>&1 | grep -c 'run_reconcile_decision' || true
docker logs deploy-control-app-1 --since 10m 2>&1 | grep -cE '对账.*失败|reconcile.*fail' || true
exit 0
