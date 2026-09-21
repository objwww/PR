#!/bin/sh
set -e
PW='Tmp#p317-0917'
cd /opt/build/pr/deploy
SBK=/tmp/env-backup-p317shot-$(date +%Y%m%dT%H%M%S)
cp .env "$SBK"; echo "$SBK" > /tmp/p317shot-bk-path
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java "$PW")
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 30
J=/tmp/p317.cookie
rm -f $J
curl -s -c $J -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN $J | tail -1 | awk '{print $NF}')
curl -s -b $J -c $J -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#p317-0917'
INC=$(curl -s -b $J "http://127.0.0.1:8080/api/v1/incidents?limit=1" | grep -oE '"incidentId":"[0-9a-f-]+"' | head -1 | cut -d'"' -f4)
echo "INC=$INC"
echo '=== 烟测1：related ==='
curl -s -w ' [%{http_code}]\n' -b $J "http://127.0.0.1:8080/api/v1/incidents/$INC/related" | head -c 700
echo
echo '=== 烟测2：timeline-merged ==='
curl -s -w ' [%{http_code}]\n' -b $J "http://127.0.0.1:8080/api/v1/incidents/$INC/timeline-merged" | head -c 1100
echo
docker logs deploy-control-app-1 --since 3m 2>&1 | grep -c 'APPLICATION FAILED' || true
