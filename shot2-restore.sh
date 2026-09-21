#!/bin/sh
set -e
BK=$(cat /tmp/shot2-bk-path)
cd /opt/build/pr/deploy
cp "$BK" .env
A=$(md5sum < .env | cut -d' ' -f1); B=$(md5sum < "$BK" | cut -d' ' -f1)
[ "$A" = "$B" ] && echo "RESTORED_OK" || echo "MISMATCH"
docker compose up -d control-app >/dev/null
sleep 30
curl -s -o /dev/null -w 'final-health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
