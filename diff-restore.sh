#!/bin/sh
cd /opt/build/pr/deploy
cp /tmp/env-backup-diff-20260916T191251 .env
A=$(md5sum < .env | cut -d' ' -f1); B=$(md5sum < /tmp/env-backup-diff-20260916T191251 | cut -d' ' -f1)
[ "$A" = "$B" ] && echo 'RESTORED_OK' || echo 'MISMATCH'
docker compose up -d control-app >/dev/null
sleep 30
curl -s -o /dev/null -w 'final-health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
