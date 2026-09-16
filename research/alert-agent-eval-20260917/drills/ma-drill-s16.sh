#!/bin/bash
# M-a T8 演练 S16（F9 掉单）：激活→等开关刷新→chaos 流量→firing→off→恢复→resolved→SQL 对账
# 在 195 上执行；token 从 .env 读取不回显
set -u
TS=$(date +%H%M%S)
SCEN="ma-t8-s16-$TS"
TOKEN=$(grep '^CHAOS_ADMIN_TOKEN=' /opt/build/pr/deploy/alert/.env | cut -d= -f2- | tr -d '\r')
DIGEST=$(printf '%s' "$SCEN" | sha256sum | cut -d' ' -f1)
CFG=$(printf 'ma-t8-drill' | sha256sum | cut -d' ' -f1)
RULE=$(printf 'arena-business.yml' | sha256sum | cut -d' ' -f1)
ARENA=127.0.0.1:8082
PROM=127.0.0.1:9090

call_chaos() {  # $1=faultType $2=action(on/off) $3=json-body
  echo "$3" | docker exec -i flagd-admin-am3 python3 -c '
import sys, urllib.request
token = sys.argv[1]
path = sys.argv[2]
body = sys.stdin.buffer.read()
req = urllib.request.Request("http://arena-chaos-admin:8080/chaos/" + path,
    data=body, headers={"Content-Type": "application/json", "X-Admin-Token": token})
try:
    r = urllib.request.urlopen(req, timeout=10)
    print(r.status, r.read().decode())
except urllib.error.HTTPError as e:
    print(e.code, e.read().decode())
' "$TOKEN" "$1/$2" 2>&1
}

echo "== STEP1 激活 F9 (scenario=$SCEN) =="
call_chaos "F9" "on" "{\"scenarioId\":\"$SCEN\",\"target\":\"chaos-\",\"ttlSeconds\":900,\"operator\":\"ma-t8-drill\",\"configDigest\":\"$CFG\",\"groundTruth\":{\"schemaVersion\":1,\"datasetVersion\":\"ma-t8-s16\",\"payloadDigest\":\"$DIGEST\",\"applicableScope\":\"order-arena\"},\"alertLabels\":{\"alertname\":\"ArenaPaymentOrderMismatch\",\"service\":\"order-arena\",\"fault_type\":\"F9\",\"severity\":\"page\"},\"ruleDigest\":\"$RULE\"}"

echo "== STEP1b 等开关缓存刷新（10s）=="
sleep 10

echo "== STEP2 chaos 流量：3 单创+付（F9 咬住：capture 成功但 markPaid 被吞）=="
for i in 1 2 3; do
  INTENT="chaos-t8s16-$TS-$i"
  RESP=$(curl -s -X POST "$ARENA/orders" -H 'Content-Type: application/json' \
    -d "{\"intentId\":\"$INTENT\",\"correlationId\":\"$INTENT\",\"buyerId\":\"buyer-drill\",\"sku\":\"sku-std\",\"quantity\":1,\"amount\":10.00}")
  echo "create[$i]: $RESP"
  OID=$(echo "$RESP" | sed -n 's/.*"orderId":"\([^"]*\)".*/\1/p')
  if [ -n "$OID" ]; then
    echo "pay[$i]: $(curl -s -X POST "$ARENA/orders/$OID/pay" -H 'Content-Type: application/json' -d "{\"correlationId\":\"$INTENT\"}")"
  fi
done

echo "== STEP3 等探测+抓取（50s）后查 gauge 与告警 =="
sleep 50
echo "gauge: $(curl -s "$PROM/api/v1/query?query=oa_payment_order_mismatch_current" | sed 's/.*"value":\[[^,]*,//;s/\].*//')"
echo "ALERTS: $(curl -s "$PROM/api/v1/query?query=ALERTS%7Balertname%3D%22ArenaPaymentOrderMismatch%22%7D" | head -c 320)"

echo "== STEP4 SQL 对账（注入期损伤事实）=="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -c "
select 'mismatch_orders=' || count(*) from (
  select distinct t.id from arena.oa_payment_record p
    join arena.oa_trade_order t on t.id = p.order_id
  where p.kind='CAPTURE' and p.result='SUCCEEDED'
    and t.booking_status='ENABLED' and t.pay_status='NOT_PAY') m;
select 'session=' || state from arena.oa_chaos_session where scenario_id='$SCEN';"

echo "== STEP5 关闭注入 =="
call_chaos "F9" "off" "{\"scenarioId\":\"$SCEN\",\"expectedGeneration\":0}"

echo "== STEP6 轮询恢复（最长 150s：arena 修复→RECOVERED 审计→chaos-admin 闭会话）=="
for n in $(seq 1 10); do
  sleep 15
  ST=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c "select state from arena.oa_chaos_session where scenario_id='$SCEN'")
  G=$(curl -s "$PROM/api/v1/query?query=oa_payment_order_mismatch_current" | sed 's/.*"value":\[[^,]*,//;s/\].*//')
  echo "poll[$n] state=$ST gauge=$G"
  if [ "$ST" = "CLOSED" ]; then break; fi
done

echo "== STEP7 终态 SQL 对账（损伤修复/告警 resolved/审计/会话）=="
echo "ALERTS_final: $(curl -s "$PROM/api/v1/query?query=ALERTS%7Balertname%3D%22ArenaPaymentOrderMismatch%22%7D" | head -c 320)"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -c "
select 'residual_mismatch' as chk, count(*) from arena.oa_payment_record p
  join arena.oa_trade_order t on t.id = p.order_id
where p.kind='CAPTURE' and p.result='SUCCEEDED'
  and t.booking_status='ENABLED' and t.pay_status='NOT_PAY'
  and t.correlation_id like 'chaos-t8s16-$TS-%'
union all
select 'drill_orders_paid', count(*) from arena.oa_trade_order
where correlation_id like 'chaos-t8s16-$TS-%' and pay_status='PAID';
select a.action, a.fault_type, a.detail from arena.oa_injection_audit a
  join arena.oa_chaos_session s on s.id=a.session_id
where s.scenario_id='$SCEN';
select state, generation from arena.oa_chaos_session where scenario_id='$SCEN';"
