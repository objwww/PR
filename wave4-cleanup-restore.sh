#!/bin/sh
set -e
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "delete from notify_silence where reason='发布窗口期间压测告警暂停'"
echo "test silence cleaned"
cd /opt/build/pr/deploy
BK=$(cat /tmp/wave4-bk-path)
cp "$BK" .env
[ "$(md5sum < .env | cut -d' ' -f1)" = "$(md5sum < "$BK" | cut -d' ' -f1)" ] && echo 'RESTORED_OK'
docker compose up -d control-app >/dev/null
sleep 35
curl -s -o /dev/null -w 'final-health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
