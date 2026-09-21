#!/bin/bash
# T9 演练公共函数库（被 s20/s21/s22/s24/s25 脚本 source）
# 用法：source ma-drill-lib.sh 后调用 drill_main <faultType> <scenarioType> <severity>
TOKEN=$(grep '^CHAOS_ADMIN_TOKEN=' /opt/build/pr/deploy/alert/.env | cut -d= -f2- | tr -d '\r')
ARENA=127.0.0.1:8082
PROM=127.0.0.1:9090

call_chaos() {  # $1=faultType $2=action $3=scenarioId $4=ttl $5=alertname $6=fault_label $7=severity $8=dataset
  local SCEN="$3"
  local DIGEST=$(printf '%s' "$SCEN" | sha256sum | cut -d' ' -f1)
  local CFG=$(printf 'ma-t8-drill' | sha256sum | cut -d' ' -f1)
  local RULE=$(printf 'arena-business.yml' | sha256sum | cut -d' ' -f1)
  echo "{\"scenarioId\":\"$SCEN\",\"target\":\"chaos-\",\"ttlSeconds\":$4,\"operator\":\"ma-t9-drill\",\"configDigest\":\"$CFG\",\"groundTruth\":{\"schemaVersion\":1,\"datasetVersion\":\"$8\",\"payloadDigest\":\"$DIGEST\",\"applicableScope\":\"order-arena\"},\"alertLabels\":{\"alertname\":\"$5\",\"service\":\"order-arena\",\"fault_type\":\"$6\",\"severity\":\"$7\"},\"ruleDigest\":\"$RULE\"}" | docker exec -i flagd-admin-am3 python3 -c '
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
create_order() {  # $1=intent -> echo orderId
  curl -s -X POST "$ARENA/orders" -H 'Content-Type: application/json' \
    -d "{\"intentId\":\"$1\",\"correlationId\":\"$1\",\"buyerId\":\"buyer-drill\",\"sku\":\"sku-std\",\"quantity\":1,\"amount\":10.00}" \
    | sed -n 's/.*"orderId":"\([^"]*\)".*/\1/p'
}
pay_order() {  # $1=orderId $2=correlation
  curl -s -X POST "$ARENA/orders/$1/pay" -H 'Content-Type: application/json' -d "{\"correlationId\":\"$2\"}"
}
gauge() { curl -s "$PROM/api/v1/query?query=$1" | sed 's/.*"value":\[[^,]*,//;s/\].*//'; }
alerts() { curl -s "$PROM/api/v1/query?query=ALERTS%7Balertname%3D%22$1%22%7D" | grep -o 'alertstate":"[a-z]*"' | sort | uniq -c; }
session_state() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c "select state from arena.oa_chaos_session where scenario_id='$1'"; }
audit_of() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -c "select a.action, a.fault_type, a.detail from arena.oa_injection_audit a join arena.oa_chaos_session s on s.id=a.session_id where s.scenario_id='$1'"; }
wait_closed() {  # $1=scenario $2=最多轮数 $3=gauge表达式
  local n=1
  while [ $n -le $2 ]; do
    sleep 15
    local ST=$(session_state "$1"); local G=$(gauge "$3")
    echo "poll[$n] state=$ST gauge=$G"
    [ "$ST" = "CLOSED" ] && return 0
    n=$((n+1))
  done
  return 1
}
