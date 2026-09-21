#!/bin/sh
set -e
cd /opt/build/pr/deploy
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java 'Tmp#v2diag-20260916')
BK=/tmp/env-backup-v2diag-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"; echo "backup=$BK"
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 35
U=$(grep '^AUTH_OPERATOR_USERNAME=' .env | cut -d= -f2- | tr -d '\r"')
J=/tmp/probe11.jar; rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#v2diag-20260916" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
echo "=== jar 中 XSRF-TOKEN 行数 ==="
grep -c "XSRF-TOKEN" $J || echo 0
curl -s -b $J -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
echo "=== 刷新后 XSRF-TOKEN 行数 ==="
grep -c "XSRF-TOKEN" $J || echo 0
T2=$(awk '$6=="XSRF-TOKEN"{print $7}' $J | tail -1)
echo "T2len=${#T2}"
echo '=== POST /diag/free ==='
curl -s --max-time 90 -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -d '{"question":"checkout 服务当前最大的风险是什么？","createdBy":"human:verify"}' \
  -w '\nhttp=%{http_code}\n' \
  http://127.0.0.1:8080/api/v1/incidents/5781021b-735a-44ca-8281-d2ccf3de6c0c/diag/free
cp "$BK" .env
A=$(md5sum < .env | cut -d' ' -f1); B=$(md5sum < "$BK" | cut -d' ' -f1)
[ "$A" = "$B" ] && echo 'RESTORED_OK'
docker compose up -d control-app >/dev/null
sleep 30
curl -s -o /dev/null -w 'final-health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
