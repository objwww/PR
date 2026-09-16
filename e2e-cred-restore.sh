#!/bin/sh
set -eu
cd /opt/build/pr/deploy
BK=/tmp/env-backup-e2e-cred-20260916T122557
M1=$(grep -E '^AUTH_OPERATOR_(USERNAME|PASSWORD_BCRYPT)=' "$BK" | md5sum | cut -d' ' -f1)
M2=$(grep -E '^AUTH_OPERATOR_(USERNAME|PASSWORD_BCRYPT)=' .env | md5sum | cut -d' ' -f1)
[ "$M1" = "$M2" ] && echo "env_match=OK ($M1)" || { echo "env_match=MISMATCH"; exit 1; }
docker compose up -d control-app 2>&1 | tail -1
for i in $(seq 1 40); do s=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || true); [ "$s" = "200" ] && { echo "health=200 after ${i}x3s"; break; }; sleep 3; done
docker exec deploy-control-app-1 sh -c 'test -n "$AUTH_OPERATOR_PASSWORD_BCRYPT" && echo container_bcrypt_set=yes'
echo "whitelist_intact=$(grep -c '^APP_ALERT_MUTATION_GUARDIAN_LOW_RISK_TOOLS=chaos.resolve$' .env)"
ls docker-compose.override.yml 2>/dev/null || echo "override_file=absent"
exit 0
