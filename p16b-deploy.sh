#!/bin/sh
set -e
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
tar xf /tmp/p16b-backend.tar -C /opt/build/pr
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -2
cd deploy
docker compose build control-app 2>&1 | tail -1
docker compose up -d control-app >/dev/null
sleep 35
i=1
while [ $i -le 6 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 10; i=$((i+1))
done
echo "health=$code"
rm -f /tmp/p16.cookie
curl -s -c /tmp/p16.cookie -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN /tmp/p16.cookie | awk '{print $NF}')
curl -s -b /tmp/p16.cookie -c /tmp/p16.cookie -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#p16-0916'
echo '=== active-plan 复验 ==='
curl -s -b /tmp/p16.cookie "http://127.0.0.1:8080/api/v1/prompt-workbench/active-plan"; echo
echo '=== assets 计数对账 ==='
curl -s -b /tmp/p16.cookie "http://127.0.0.1:8080/api/v1/prompt-workbench/assets?limit=200" | head -c 120; echo
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from release_asset where asset_kind='PROMPT';"
