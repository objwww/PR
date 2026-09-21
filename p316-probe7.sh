#!/bin/sh
J=/tmp/p316.cookie
curl -s -o /dev/null -b $J "http://127.0.0.1:8080/api/agent-ops/costs"
sleep 2
docker logs deploy-control-app-1 --since 2m 2>&1 | grep -A30 -m1 'bad SQL grammar' | grep -E 'Caused by|ERROR:|Position|where|sum|usage' | head -12
