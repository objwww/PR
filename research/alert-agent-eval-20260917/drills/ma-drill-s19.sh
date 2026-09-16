#!/bin/bash
# M-a T8 演练 S19（F12 对账不平）：激活→缺 DISCOUNT 扣减行→firing→off→补记账→resolved→SQL 对账
set -u
TS=$(date +%H%M%S)
SCEN="ma-t8-s19-$TS"
TOKEN=$(grep '^CHAOS_ADMIN_TOKEN=' /opt/build/pr/deploy/alert/.env | cut -d= -f2- | tr -d '\r')
DIGEST=$(printf '%s' "$SCEN" | sha256sum | cut -d' ' -f1)
CFG=$(printf 'ma-t8-drill' | sha256sum | cut -d' ' -f1)
RULE=$(printf 'arena-business.yml' | sha256sum | cut -d' ' -f1)
ARENA=127.0.0.1:8082
PROM=127.0.0.1:9090

call_chaos() {
  echo "$3" | docker exec -i flagd-admin-am3 python3 -c '
import sys, urllib.request
token = sys.argv[1]; path = sys.argv[2]; body = sys.stdin.buffer.read()
req = urllib.request.Request("http://arena-chaos-admin:8080/chaos/" + path,
    data=body, headers={"Content-Type": "application/json", "X-Admin-Token": token})
try:
    r = urllib.request.urlopen(req, timeout=10); print(r.status, r.read().decode())
except urllib.error.HTTPError as e:
    print(e.code, e.read().decode())
' "$TOKEN" "$1/$2" 2>&1
}

echo "== STEP1 激活 F12 (scenario=$SCEN) =="
call_chaos "F12" "on" "{\"scenarioId\":\"$SCEN\",\"target\":\"chaos-\",\"ttlSeconds\":900,\"operator\":\"ma-t8-drill\",\"configDigest\":\"$CFG\",\"groundTruth\":{\"schemaVersion\":1,\"datasetVersion\":\"ma-t8-s19\",\"payloadDigest\":\"$DIGEST\",\"applicableScope\":\"order-arena\"},\"alertLabels\":{\"alertname\":\"ArenaReconciliationDiff\",\"service\":\"order-arena\",\"fault_type\":\"F12\",\"severity\":\"page\"},\"ruleDigest\":\"$RULE\"}"
sleep 10

echo "== STEP2 chaos 流量：创单（DISCOUNT 扣减行被吞）→支付推进 =="
RESP=$(curl -s -X POST "$ARENA/orders" -H 'Content-Type: application/json' \
  -d "{\"intentId\":\"chaos-t8s19-$TS-1\",\"correlationId\":\"chaos-t8s19-$TS-1\",\"buyerId\":\"buyer-drill\",\"sku\":\"sku-std\",\"quantity\":1,\"amount\":10.00}")
echo "create: $RESP"
OID=$(echo "$RESP" | sed -n 's/.*"orderId":"\([^"]*\)".*/\1/p')
[ -n "$OID" ] && echo "pay: $(curl -s -X POST "$ARENA/orders/$OID/pay" -H 'Content-Type: application/json' -d "{\"correlationId\":\"chaos-t8s19-$TS-1\"}")"

echo "== STEP3 等探测+抓取（50s）=="
sleep 50
echo "gauge: $(curl -s "$PROM/api/v1/query?query=oa_recon_diff_current" | sed 's/.*"value":\[[^,]*,//;s/\].*//')"
echo "ALERTS: $(curl -s "$PROM/api/v1/query?query=ALERTS%7Balertname%3D%22ArenaReconciliationDiff%22%7D" | head -c 300)"

echo "== STEP4 SQL 对账（差异明细 = /orders/recon/diffs 值源）=="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -c "
select t.id::text order_id, count(distinct l.resource_type) deducted_types
from arena.oa_trade_order t left join arena.oa_resource_ledger l
  on l.order_id=t.id and l.direction='DEDUCT'
where t.correlation_id like 'chaos-t8s19-$TS-%' group by t.id;
select 'session=' || state from arena.oa_chaos_session where scenario_id='$SCEN';"

echo "== STEP5 关闭注入 =="
call_chaos "F12" "off" "{\"scenarioId\":\"$SCEN\",\"expectedGeneration\":0}"

echo "== STEP6 轮询恢复（最长 150s）=="
for n in $(seq 1 10); do
  sleep 15
  ST=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c "select state from arena.oa_chaos_session where scenario_id='$SCEN'")
  G=$(curl -s "$PROM/api/v1/query?query=oa_recon_diff_current" | sed 's/.*"value":\[[^,]*,//;s/\].*//')
  echo "poll[$n] state=$ST gauge=$G"
  [ "$ST" = "CLOSED" ] && break
done

echo "== STEP7 终态 SQL 对账 =="
echo "ALERTS_final: $(curl -s "$PROM/api/v1/query?query=ALERTS%7Balertname%3D%22ArenaReconciliationDiff%22%7D" | head -c 200)"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -c "
select 'drill_orders_undertype' as chk, count(*) from (
  select t.id from arena.oa_trade_order t left join arena.oa_resource_ledger l
    on l.order_id=t.id and l.direction='DEDUCT'
  where t.correlation_id like 'chaos-t8s19-$TS-%'
  group by t.id having count(distinct l.resource_type) < 4) d;
select a.action, a.fault_type, a.detail from arena.oa_injection_audit a
  join arena.oa_chaos_session s on s.id=a.session_id where s.scenario_id='$SCEN';
select state from arena.oa_chaos_session where scenario_id='$SCEN';"
