#!/bin/sh
set -e
J=/tmp/p317.cookie
mint() { curl -s -b $J -c $J -o /dev/null http://127.0.0.1:8080/api/auth/csrf; grep XSRF-TOKEN $J | tail -1 | awk '{print $NF}'; }
T=$(mint)
echo '=== 发起演练（真接口真账本） ==='
curl -s -w ' [%{http_code}]\n' -X POST "http://127.0.0.1:8080/api/drills" -b $J -c $J \
  -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' \
  -d "{\"idempotencyKey\":\"p317-merged-axis-$(date +%s)\",\"scenarioId\":\"S1\",\"targetEnv\":\"arena-195\",\"durationSeconds\":60}"
echo '=== 复核：timeline-merged DRILL 组点亮 ==='
INC=c2070875-1fdb-4320-84cd-ca2b4113bb70
curl -s -b $J "http://127.0.0.1:8080/api/v1/incidents/$INC/timeline-merged" | head -c 1200
echo
