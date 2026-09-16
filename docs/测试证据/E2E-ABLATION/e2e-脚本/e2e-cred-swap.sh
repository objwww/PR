#!/bin/sh
set -e
cd /opt/build/pr/deploy
cp .env /tmp/env-backup-e2e-cred-$(date +%Y%m%dT%H%M%S)
sed -i 's/^AUTH_OPERATOR_USERNAME=.*/AUTH_OPERATOR_USERNAME=operator/' .env
sed -i 's/^AUTH_OPERATOR_PASSWORD_BCRYPT=.*/AUTH_OPERATOR_PASSWORD_BCRYPT=$$2a$$10$$XYv0PhQ60xu0aI8S\/MlGSO\/\/jvVykSfOAdXAXG6FwG4eHDFHoHwgO/' .env
grep -c '^AUTH_OPERATOR_USERNAME=operator' .env
docker compose up -d control-app 2>&1 | tail -1
sleep 35
for i in 1 2 3 4 5; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  echo "health$i=$code"; [ "$code" = "200" ] && break; sleep 12
done
curl -s -c /tmp/e2e-cookie.txt -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"operator","password":"operator"}'
