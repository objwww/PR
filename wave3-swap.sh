#!/bin/sh
# 波次3验证轮临时置换（备份→置换→重启→health；恢复由 cred-restore 对拍收尾）
set -e
cd /opt/build/pr/deploy
BK=/tmp/env-backup-wave3-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"; echo "backup=$BK"
[ "$(md5sum < .env | cut -d' ' -f1)" = "$(md5sum < "$BK" | cut -d' ' -f1)" ] && echo 'backup md5=OK'
sed -i 's|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$$2a$$10$$h/xWOcwcop5gJS2preaqaOvAC5IP2auPAY79G70IFgxS4PjtJTy.i|' .env
docker compose up -d control-app >/dev/null
code=000
for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
  sleep 10
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
done
echo "health=$code"
