#!/bin/sh
set -e
cd /opt/build/pr/deploy
BK=$(cat /tmp/p315shot-bk-path)
cp "$BK" .env
docker compose up -d control-app >/dev/null 2>&1
sleep 35
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo "restored_from=$BK"
