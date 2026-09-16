#!/bin/sh
set -eu
BK=$(ls -t /tmp/env-backup-e2e-cred-* | head -1)
cd /opt/build/pr/deploy
for f in AUTH_OPERATOR_USERNAME AUTH_OPERATOR_PASSWORD_BCRYPT; do
  LINE=$(grep "^${f}=" "$BK" | head -1)
  sed -i "s|^${f}=.*|$LINE|" .env
done
M1=$(grep -E '^AUTH_OPERATOR_(USERNAME|PASSWORD_BCRYPT)=' "$BK" | md5sum | cut -d' ' -f1)
M2=$(grep -E '^AUTH_OPERATOR_(USERNAME|PASSWORD_BCRYPT)=' .env | md5sum | cut -d' ' -f1)
[ "$M1" = "$M2" ] && echo RESTORED_OK || { echo MISMATCH; exit 1; }
docker compose up -d control-app 2>&1 | tail -1
for i in $(seq 1 30); do s=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || true); [ "$s" = "200" ] && { echo health=200; break; }; sleep 3; done
exit 0
