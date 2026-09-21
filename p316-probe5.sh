#!/bin/sh
J=/tmp/p316.cookie
MARK=$(date +%H:%M:%S)
echo "marker=$MARK"
curl -s -o /dev/null -w 'costs=%{http_code}\n' -b $J "http://127.0.0.1:8080/api/agent-ops/costs"
sleep 2
docker logs deploy-control-app-1 --since 1m 2>&1 | tail -40
