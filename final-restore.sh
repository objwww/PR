#!/bin/sh
set -e
BK=$(cat /tmp/final-bk-path)
cd /opt/build/pr/deploy
cp "$BK" .env
[ "$(md5sum < .env | cut -d' ' -f1)" = "$(md5sum < "$BK" | cut -d' ' -f1)" ] && echo 'RESTORED_OK'
docker compose up -d control-app >/dev/null
sleep 35
curl -s -o /dev/null -w 'final-health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
