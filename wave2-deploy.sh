#!/bin/sh
# 前端产品化波次2 部署：V122 迁移 + 标注/分析端点 + 分析页
set -eu
cd /opt/build/pr/deploy
docker compose build control-app web 2>&1 | tail -1
docker compose up migrate 2>&1 | tail -1
docker compose up -d control-app web 2>&1 | tail -1
for i in $(seq 1 40); do s=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || true); [ "$s" = "200" ] && { echo "health=200 after ${i}x3s"; break; }; sleep 3; done
echo '--- flyway ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success from flyway_schema_history where success order by installed_rank desc limit 1"
echo '--- trends ---'
BEARER=$(docker exec deploy-control-app-1 sh -c 'echo $APP_OPERATOR_API_BEARER')
curl -s http://127.0.0.1:8080/api/v1/analytics/trends -H "Authorization: Bearer $BEARER" | head -c 600
echo
curl -s -o /dev/null -w 'web=%{http_code}\n' http://127.0.0.1:8090/
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -cE 'APPLICATION FAILED' || true
exit 0
