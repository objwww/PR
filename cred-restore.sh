#!/bin/sh
# 恢复正式凭据（md5 对拍备份）+ 重启 + health
set -e
cd /opt/build/pr/deploy
BK=/tmp/env-backup-linkfix2-20260916T163715
cp "$BK" .env
[ "$(md5sum < .env | cut -d' ' -f1)" = "$(md5sum < "$BK" | cut -d' ' -f1)" ] && echo 'RESTORED_OK'
docker compose up -d control-app >/dev/null
code=000
for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
  sleep 10
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
done
echo "final-health=$code"
docker logs deploy-control-app-1 --since 3m 2>&1 | grep -E 'Started .*Application' | tail -1
