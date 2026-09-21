#!/bin/sh
# 临时置换口令（哈希经镜像内 spring-security-crypto 生成；compose .env 值 $$ 转义约定 B-37）
set -e
cd /opt/build/pr/deploy
BK=/tmp/env-backup-linkfix2-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"; echo "backup=$BK"
[ "$(md5sum < .env | cut -d' ' -f1)" = "$(md5sum < "$BK" | cut -d' ' -f1)" ] && echo 'backup md5=OK'
sed -i 's|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$$2a$$10$$rqGtm1n.XUOgWsdo5IqC.Ow322fgSHiTaq2c79pQolNNjTO1FEhHy|' .env
docker compose up -d control-app >/dev/null
code=000
for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
  sleep 10
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
done
echo "health=$code"
