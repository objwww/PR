#!/bin/sh
J=/tmp/p316.cookie
curl -s -o /dev/null -w 'costs=%{http_code}\n' -b $J "http://127.0.0.1:8080/api/agent-ops/costs"
sleep 2
docker logs deploy-control-app-1 --since 1m 2>&1 | grep -B3 -m1 'Exception' 
echo '=== 异常行与前 12 行堆栈:'
docker logs deploy-control-app-1 --since 2m 2>&1 | grep -A12 -m1 -E '^com\.|^org\.springframework\.(web|dao|security|http).*Exception|Exception:' | head -20
