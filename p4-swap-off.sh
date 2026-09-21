#!/bin/sh
cd /opt/build/pr/deploy
BK=$(ls -t /tmp/env-backup-p4shot-* | head -1)
cp "$BK" .env
echo "restored-from=$BK"
N=$(grep -c 'Tmp#p4rt' .env || true)
echo "temp-pwd-occurrences=$N"
docker compose up -d control-app >/dev/null 2>&1
sleep 28
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
