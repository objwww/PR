#!/bin/bash
# M-a T8 演练 S17（F10 支付悬挂）：激活→25 单 AUTH 沉默→积压>20 for 5m→ticket firing
# →off→AUTH 置 UNKNOWN 交 F3 对账收敛→resolved→SQL 对账。全程约 10 分钟。
set -u
TS=$(date +%H%M%S)
SCEN="ma-t8-s17-$TS"
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

echo "== STEP1 激活 F10 (scenario=$SCEN) =="
call_chaos "F10" "on" "{\"scenarioId\":\"$SCEN\",\"target\":\"chaos-\",\"ttlSeconds\":1800,\"operator\":\"ma-t8-drill\",\"configDigest\":\"$CFG\",\"groundTruth\":{\"schemaVersion\":1,\"datasetVersion\":\"ma-t8-s17\",\"payloadDigest\":\"$DIGEST\",\"applicableScope\":\"order-arena\"},\"alertLabels\":{\"alertname\":\"ArenaPendingPaymentBacklog\",\"service\":\"order-arena\",\"fault_type\":\"F10\",\"severity\":\"ticket\"},\"ruleDigest\":\"$RULE\"}"
sleep 10

echo "== STEP2 chaos 流量：25 单创单（AUTH 沉默 INITIATED，订单停 CREATED）=="
for i in $(seq 1 25); do
  curl -s -o /dev/null -X POST "$ARENA/orders" -H 'Content-Type: application/json' \
    -d "{\"intentId\":\"chaos-t8s17-$TS-$i\",\"correlationId\":\"chaos-t8s17-$TS-$i\",\"buyerId\":\"buyer-drill\",\"sku\":\"sku-std\",\"quantity\":1,\"amount\":10.00}" &
done
wait
echo "25 单已投递"

echo "== STEP3 等 75s（授权超龄 60s + 探测窗）查 gauge =="
sleep 75
echo "gauge: $(curl -s "$PROM/api/v1/query?query=oa_pending_payment_orders_current" | sed 's/.*"value":\[[^,]*,//;s/\].*//')"

echo "== STEP4 等待 ticket firing（for 5m，轮询最长 8 分钟）=="
FIRED=0
for n in $(seq 1 32); do
  sleep 15
  A=$(curl -s "$PROM/api/v1/query?query=ALERTS%7Balertname%3D%22ArenaPendingPaymentBacklog%2Cseverity%3D%22%22ticket%22%22%7D" | head -c 60)
  A=$(curl -s "$PROM/api/v1/query?query=ALERTS%7Balertname%3D%22ArenaPendingPaymentBacklog%22%7D" | head -c 260)
  echo "poll[$n] $A"
  case "$A" in *firing*) FIRED=1; break;; esac
done
echo "FIRED=$FIRED"

echo "== STEP5 关闭注入 =="
call_chaos "F10" "off" "{\"scenarioId\":\"$SCEN\",\"expectedGeneration\":0}"

echo "== STEP6 轮询恢复（最长 240s：置UNKNOWN→F3 对账收敛→闭会话）=="
for n in $(seq 1 16); do
  sleep 15
  ST=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c "select state from arena.oa_chaos_session where scenario_id='$SCEN'")
  G=$(curl -s "$PROM/api/v1/query?query=oa_pending_payment_orders_current" | sed 's/.*"value":\[[^,]*,//;s/\].*//')
  echo "poll[$n] state=$ST gauge=$G"
  [ "$ST" = "CLOSED" ] && break
done

echo "== STEP7 终态 SQL 对账 =="
echo "ALERTS_final: $(curl -s "$PROM/api/v1/query?query=ALERTS%7Balertname%3D%22ArenaPendingPaymentBacklog%22%7D" | head -c 200)"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -c "
select 'stuck_initiated' as chk, count(*) from arena.oa_payment_record p
  join arena.oa_trade_order t on t.id=p.order_id
  where p.kind='AUTH' and p.result='INITIATED' and t.correlation_id like 'chaos-t8s17-$TS-%'
union all
select 'unsettled_auth', count(*) from arena.oa_payment_record p
  join arena.oa_trade_order t on t.id=p.order_id
  where p.kind='AUTH' and p.result in ('UNKNOWN','RECONCILING') and t.correlation_id like 'chaos-t8s17-$TS-%'
union all
select 'drill_orders_discarded', count(*) from arena.oa_trade_order
  where correlation_id like 'chaos-t8s17-$TS-%' and booking_status='DISCARDED'
union all
select 'drill_orders_enabled', count(*) from arena.oa_trade_order
  where correlation_id like 'chaos-t8s17-$TS-%' and booking_status='ENABLED';
select a.action, a.fault_type, a.detail from arena.oa_injection_audit a
  join arena.oa_chaos_session s on s.id=a.session_id where s.scenario_id='$SCEN';
select state from arena.oa_chaos_session where scenario_id='$SCEN';"
