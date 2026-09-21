#!/bin/sh
# 3.12 部署 + 口令轮换 + 烟测（p312-audit.sh 收口恢复；无新迁移——洞察读面零 DDL）
set -e
PW='Tmp#p312-0916'
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
cd /tmp && tr -d '\r' < p312-batch.tar.md5 | md5sum -c -
cd /opt/build/pr
tar xf /tmp/p312-batch.tar -C /opt/build/pr
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -2
cd deploy
docker compose build control-app web 2>&1 | tail -2
docker compose up -d control-app web 2>&1 | tail -2
sleep 40
i=1
while [ $i -le 8 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 10; i=$((i+1))
done
echo "health=$code"
SBK=/tmp/env-backup-p312shot-$(date +%Y%m%dT%H%M%S)
cp .env "$SBK"; echo "$SBK" > /tmp/p312shot-bk-path
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java "$PW")
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 30
rm -f /tmp/p312.cookie
curl -s -c /tmp/p312.cookie -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN /tmp/p312.cookie | awk '{print $NF}')
curl -s -b /tmp/p312.cookie -c /tmp/p312.cookie -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#p312-0916'
echo '=== 烟测：matrix + four-phase ==='
curl -s -b /tmp/p312.cookie "http://127.0.0.1:8080/api/v1/drill-matrix" | head -c 600; echo
curl -s -b /tmp/p312.cookie "http://127.0.0.1:8080/api/v1/drill-matrix/four-phase" | head -c 400; echo
docker logs deploy-control-app-1 --since 3m 2>&1 | grep -c 'APPLICATION FAILED' || true
