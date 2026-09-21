#!/bin/sh
# 3.13 部署（V135 迁移）+ 口令轮换 + 烟测
set -e
PW='Tmp#p313-0916'
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
cd /tmp && tr -d '\r' < p313-batch.tar.md5 | md5sum -c -
cd /opt/build/pr
tar xf /tmp/p313-batch.tar -C /opt/build/pr
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -2
cd deploy
docker compose build control-app web 2>&1 | tail -2
docker compose up migrate 2>&1 | tail -1
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version from flyway_schema_history where success order by installed_rank desc limit 1;"
docker compose up -d control-app web 2>&1 | tail -2
sleep 40
i=1
while [ $i -le 8 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 10; i=$((i+1))
done
echo "health=$code"
SBK=/tmp/env-backup-p313shot-$(date +%Y%m%dT%H%M%S)
cp .env "$SBK"; echo "$SBK" > /tmp/p313shot-bk-path
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java "$PW")
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 30
rm -f /tmp/p313.cookie
curl -s -c /tmp/p313.cookie -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN /tmp/p313.cookie | awk '{print $NF}')
curl -s -b /tmp/p313.cookie -c /tmp/p313.cookie -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#p313-0916'
echo '=== 烟测：catalog 投影（30天/MTTR/owner） ==='
curl -s -b /tmp/p313.cookie "http://127.0.0.1:8080/api/v1/catalog" | head -c 500; echo
echo '=== owner 写面（登记→读回） ==='
SVC=$(curl -s -b /tmp/p313.cookie "http://127.0.0.1:8080/api/v1/catalog" | grep -oE '"service":"[^"]+"' | head -1 | cut -d'"' -f4)
echo "sample_service=$SVC"
curl -s -b /tmp/p313.cookie -X POST "http://127.0.0.1:8080/api/v1/catalog/owner" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d "{\"service\":\"$SVC\",\"owner\":\"checkout-oncall\",\"note\":\"3.13 验收登记\"}"; echo
curl -s -b /tmp/p313.cookie "http://127.0.0.1:8080/api/v1/catalog" | head -c 300; echo
docker logs deploy-control-app-1 --since 3m 2>&1 | grep -c 'APPLICATION FAILED' || true
