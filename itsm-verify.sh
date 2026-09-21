#!/bin/sh
set -e
cd /opt/build/pr/deploy
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java 'Tmp#itsmv-20260917')
BK=/tmp/env-backup-itsmv-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 35
U=$(grep '^AUTH_OPERATOR_USERNAME=' .env | cut -d= -f2- | tr -d '\r"')
J=/tmp/probe17.jar; rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#itsmv-20260917" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
curl -s -b $J -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T2=$(awk '$6=="XSRF-TOKEN"{print $7}' $J | tail -1)
IID=$(curl -s -b $J http://127.0.0.1:8080/api/v1/postmortems | python3 -c "import json,sys;d=json.load(sys.stdin);print(d['items'][0]['incidentId'])")
echo "incident=$IID"
echo '=== POST itsm draft ==='
curl -s --max-time 30 -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST "http://127.0.0.1:8080/api/v1/itsm/incidents/$IID/draft?createdBy=human:verify" \
  -w '\nhttp=%{http_code}\n'
echo '=== GET tickets ==='
curl -s -b $J http://127.0.0.1:8080/api/v1/itsm/tickets | head -c 400
echo
cp "$BK" .env
A=$(md5sum < .env | cut -d' ' -f1); B=$(md5sum < "$BK" | cut -d' ' -f1)
[ "$A" = "$B" ] && echo 'RESTORED_OK'
docker compose up -d control-app >/dev/null
sleep 28
curl -s -o /dev/null -w 'final-health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
