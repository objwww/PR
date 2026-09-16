#!/bin/sh
# 前端产品化波次1 部署：overlay + build control-app & web + up + health
set -eu
cd /opt/build/pr/alert-web
tar xzf /tmp/web-overlay.tgz
echo '--- overlay files ---'
ls -la src/dict src/views/ApprovalOpsView.vue
cd /opt/build/pr/deploy
docker compose build control-app web 2>&1 | tail -3
docker compose up -d control-app web 2>&1 | tail -2
for i in $(seq 1 40); do s=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || true); [ "$s" = "200" ] && { echo "health=200 after ${i}x3s"; break; }; sleep 3; done
curl -s -o /dev/null -w 'web=%{http_code}\n' http://127.0.0.1:8090/
echo '--- 三个新查询端点（operator bearer） ---'
BEARER=$(docker exec deploy-control-app-1 sh -c 'echo $APP_OPERATOR_API_BEARER')
curl -s http://127.0.0.1:8080/api/mutation/pending-approvals -H "Authorization: Bearer $BEARER" | head -c 400; echo
curl -s http://127.0.0.1:8080/api/mutation/escalations -H "Authorization: Bearer $BEARER" | head -c 300; echo
curl -s http://127.0.0.1:8080/api/inbox-admin/quarantined -H "Authorization: Bearer $BEARER" | head -c 400; echo
curl -s http://127.0.0.1:8080/api/agent-ops/summary -H "Authorization: Bearer $BEARER" | head -c 300; echo
curl -s http://127.0.0.1:8080/api/agent-ops/latency-layers -H "Authorization: Bearer $BEARER" | head -c 300; echo
echo '--- FAILED=0 检查 ---'
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -cE 'APPLICATION FAILED' || true
exit 0
