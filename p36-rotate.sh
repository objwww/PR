#!/bin/sh
# 3.6 截图临时口令轮换 + 部署 + 烟测（p36-audit.sh 收口恢复）
set -e
PW='Tmp#cfg36-0916'
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
cd /tmp && tr -d '\r' < cfg36-batch.tar.md5 | md5sum -c -
cd /opt/build/pr
BK=/tmp/env-backup-cfg36-$(date +%Y%m%dT%H%M%S)
cp deploy/.env "$BK"; BEFORE=$(wc -l < deploy/.env)
tar xf /tmp/cfg36-batch.tar -C /opt/build/pr
cmp -s deploy/.env "$BK" && echo '.env intact=OK'
# 先部署（新读面），再轮换口令供截图
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -2
cd deploy
docker compose build control-app web 2>&1 | tail -2
docker compose up migrate 2>&1 | tail -1
docker compose up -d control-app web 2>&1 | tail -2
sleep 40
i=1
while [ $i -le 8 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 10; i=$((i+1))
done
echo "health=$code"
SBK=/tmp/env-backup-cfg36shot-$(date +%Y%m%dT%H%M%S)
cp .env "$SBK"; echo "$SBK" > /tmp/cfg36shot-bk-path
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java "$PW")
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 30
i=1
while [ $i -le 6 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 10; i=$((i+1))
done
echo "health=$code rotated=OK"
rm -f /tmp/c36.cookie
curl -s -c /tmp/c36.cookie -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN /tmp/c36.cookie | awk '{print $NF}')
curl -s -b /tmp/c36.cookie -c /tmp/c36.cookie -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#cfg36-0916'
echo '=== 烟测：intake + category-stats ==='
curl -s -b /tmp/c36.cookie "http://127.0.0.1:8080/api/v1/config/intake" | head -c 500; echo
curl -s -b /tmp/c36.cookie "http://127.0.0.1:8080/api/v1/config/category-stats" | head -c 400; echo
docker logs deploy-control-app-1 --since 3m 2>&1 | grep -c 'APPLICATION FAILED' || true
