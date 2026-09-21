#!/bin/sh
set -eu
cd /opt/build/pr/deploy
docker compose build control-app 2>&1 | tail -1
docker compose up -d control-app 2>&1 | tail -1
for i in $(seq 1 40); do s=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || true); [ "$s" = "200" ] && { echo "health=200 after ${i}x3s"; break; }; sleep 3; done
BEARER=$(docker exec deploy-control-app-1 sh -c 'echo $APP_OPERATOR_API_BEARER')
echo '--- 分层延迟 ---'
curl -s http://127.0.0.1:8080/api/agent-ops/latency-layers -H "Authorization: Bearer $BEARER"
echo
echo '--- 模型调用切源复核 ---'
curl -s http://127.0.0.1:8080/api/agent-ops/summary -H "Authorization: Bearer $BEARER" | head -c 260
echo
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -cE 'APPLICATION FAILED' || true
exit 0
