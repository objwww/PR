#!/bin/bash
# T9 演练公共函数库（场景脚本 source）
TOKEN=$(grep '^CHAOS_ADMIN_TOKEN=' /opt/build/pr/deploy/alert/.env | cut -d= -f2- | tr -d '\r')
ARENA=127.0.0.1:8082
PROM=127.0.0.1:9090

_chaos_api() {  # $1=path  $2=body
  echo "$2" | docker exec -i flagd-admin-am3 python3 -c '
import sys, urllib.request
token = sys.argv[1]; path = sys.argv[2]; body = sys.stdin.buffer.read()
req = urllib.request.Request("http://arena-chaos-admin:8080/chaos/" + path,
    data=body, headers={"Content-Type": "application/json", "X-Admin-Token": token})
try:
    r = urllib.request.urlopen(req, timeout=10); print(r.status, r.read().decode())
except urllib.error.HTTPError as e:
    print(e.code, e.read().decode())
' "$TOKEN" "$1" 2>&1
}

chaos_on() {  # $1=fault $2=scen $3=ttl $4=alertname $5=label $6=severity $7=dataset
  local DIGEST=$(printf '%s' "$2" | sha256sum | cut -d' ' -f1)
  local CFG=$(printf 'ma-t8-drill' | sha256sum | cut -d' ' -f1)
  local RULE=$(printf 'arena-business.yml' | sha256sum | cut -d' ' -f1)
  _chaos_api "$1/on" "{\"scenarioId\":\"$2\",\"target\":\"chaos-\",\"ttlSeconds\":$3,\"operator\":\"ma-t9-drill\",\"configDigest\":\"$CFG\",\"groundTruth\":{\"schemaVersion\":1,\"datasetVersion\":\"$7\",\"payloadDigest\":\"$DIGEST\",\"applicableScope\":\"order-arena\"},\"alertLabels\":{\"alertname\":\"$4\",\"service\":\"order-arena\",\"fault_type\":\"$5\",\"severity\":\"$6\"},\"ruleDigest\":\"$RULE\"}"
}

chaos_off() {  # $1=fault $2=scen
  _chaos_api "$1/off" "{\"scenarioId\":\"$2\",\"expectedGeneration\":0}"
}

create_order() {  # $1=intent -> orderId
  curl -s -X POST "$ARENA/orders" -H 'Content-Type: application/json' \
    -d "{\"intentId\":\"$1\",\"correlationId\":\"$1\",\"buyerId\":\"buyer-drill\",\"sku\":\"sku-std\",\"quantity\":1,\"amount\":10.00}" \
    | sed -n 's/.*"orderId":"\([^"]*\)".*/\1/p'
}

pay_order() {
  curl -s -X POST "$ARENA/orders/$1/pay" -H 'Content-Type: application/json' -d "{\"correlationId\":\"$2\"}"
}

gauge() { curl -s -G --data-urlencode "query=$1" "$PROM/api/v1/query" | sed 's/.*"value":\[[^,]*,//;s/\].*//'; }

alerts() { curl -s -G --data-urlencode "query=ALERTS{alertname=\"$1\"}" "$PROM/api/v1/query" | grep -o '"alertstate":"[a-z]*"' | sort | uniq -c; }

firing_count() {  # $1=alertname $2=range（回溯 firing 证据）
  curl -s -G --data-urlencode "query=count_over_time(ALERTS{alertname=\"$1\",alertstate=\"firing\"}[$2])" \
    "$PROM/api/v1/query" | sed 's/.*"value":\[[^,]*,//;s/\].*//'
}

session_state() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c "select state from arena.oa_chaos_session where scenario_id='$1'"; }

audit_of() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -c "select a.action, a.fault_type, a.detail from arena.oa_injection_audit a join arena.oa_chaos_session s on s.id=a.session_id where s.scenario_id='$1'"; }

wait_closed() {  # $1=scen $2=轮数 $3=gauge expr
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
