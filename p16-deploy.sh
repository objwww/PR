#!/bin/sh
# 3.16 Prompt 工作台批：读面控制器 + 前端新页（无新迁移）
set -e
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
cd /tmp && tr -d '\r' < p16-batch.tar.md5 | md5sum -c -
cd /opt/build/pr
BK=/tmp/env-backup-p16-$(date +%Y%m%dT%H%M%S)
cp deploy/.env "$BK"; BEFORE=$(wc -l < deploy/.env)
tar xf /tmp/p16-batch.tar -C /opt/build/pr
cmp -s deploy/.env "$BK" && echo '.env intact=OK'
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
rm -f /tmp/p16.cookie
curl -s -c /tmp/p16.cookie -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN /tmp/p16.cookie | awk '{print $NF}')
curl -s -b /tmp/p16.cookie -c /tmp/p16.cookie -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#p16-0916'
echo '=== 烟测：prompt-workbench assets/active-plan ==='
curl -s -b /tmp/p16.cookie "http://127.0.0.1:8080/api/v1/prompt-workbench/assets?limit=3" | head -c 700; echo
curl -s -b /tmp/p16.cookie "http://127.0.0.1:8080/api/v1/prompt-workbench/active-plan" | head -c 400; echo
docker logs deploy-control-app-1 --since 3m 2>&1 | grep -c 'APPLICATION FAILED' || true
