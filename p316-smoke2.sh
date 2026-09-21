#!/bin/sh
J=/tmp/p316.cookie
i=1
while [ $i -le 10 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 5; i=$((i+1))
done
echo "health=$code"
rm -f $J
curl -s -c $J -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN $J | tail -1 | awk '{print $NF}')
curl -s -b $J -c $J -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#p316-0917'
echo '=== costs（修复后） ==='
curl -s -w ' [%{http_code}]\n' -b $J "http://127.0.0.1:8080/api/agent-ops/costs"
echo '=== 回归：latency / risk ==='
curl -s -o /dev/null -w 'latency=%{http_code}\n' -b $J "http://127.0.0.1:8080/api/agent-ops/latency-layers"
curl -s -o /dev/null -w 'risk=%{http_code}\n' -b $J "http://127.0.0.1:8080/api/agent-ops/risk-events"
