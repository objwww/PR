#!/bin/sh
set -eu
cd /opt/build/pr/deploy
docker compose build control-app 2>&1 | tail -1
docker compose up -d control-app 2>&1 | tail -1
for i in $(seq 1 30); do s=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || true); [ "$s" = "200" ] && { echo health=200; break; }; sleep 3; done
BEARER=$(docker exec deploy-control-app-1 sh -c 'echo $APP_OPERATOR_API_BEARER')
curl -s http://127.0.0.1:8080/api/inbox-admin/quarantined -H "Authorization: Bearer $BEARER" | head -c 350
echo
exit 0
