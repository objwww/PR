#!/bin/sh
# 从本轮换号备份（T145003，含原始 AUTH_OPERATOR 值）还原 .env 并重建
set -eu
cd /opt/build/pr/deploy
BK=/tmp/env-backup-e2e-cred-20260916T145003
for f in AUTH_OPERATOR_USERNAME AUTH_OPERATOR_PASSWORD_BCRYPT; do
  LINE=$(grep "^${f}=" "$BK" | head -1)
  sed -i "s|^${f}=.*|$LINE|" .env
done
M1=$(grep -E '^AUTH_OPERATOR_(USERNAME|PASSWORD_BCRYPT)=' "$BK" | md5sum | cut -d' ' -f1)
M2=$(grep -E '^AUTH_OPERATOR_(USERNAME|PASSWORD_BCRYPT)=' .env | md5sum | cut -d' ' -f1)
[ "$M1" = "$M2" ] && echo "env_match=OK" || { echo env_match=MISMATCH; exit 1; }
docker compose up -d control-app 2>&1 | tail -1
for i in $(seq 1 40); do s=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || true); [ "$s" = "200" ] && { echo "health=200 after ${i}x3s"; break; }; sleep 3; done
echo "username_len=$(grep '^AUTH_OPERATOR_USERNAME=' .env | head -1 | cut -d= -f2 | wc -c)"
docker exec deploy-control-app-1 sh -c 'test -n "$AUTH_OPERATOR_PASSWORD_BCRYPT" && echo bcrypt_set=yes'
exit 0
