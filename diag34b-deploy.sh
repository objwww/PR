#!/bin/sh
# diag34b：坏 SQL 修复（incident 无 alertname/service 列——labels 提取契约）重部署
set -e
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
cd /tmp && echo '030ad5a0859c4cfef2bb6dd3bb1a8c93  diag34b-backend.tar' | md5sum -c -
cd /opt/build/pr
tar xf /tmp/diag34b-backend.tar -C /opt/build/pr
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
# 端到端复验：登录 → 现取 csrf → impact 快捷问（原坏 SQL 路径）→ 自由问（真模型）
PW='Tmp#diag34-0916'
RID_INC=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select r.incident_id from rca_run r where r.state='SUCCEEDED' limit 1" | tr -d '[:space:]')
rm -f /tmp/d34e.cookie
curl -s -c /tmp/d34e.cookie -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN /tmp/d34e.cookie | awk '{print $NF}')
curl -s -b /tmp/d34e.cookie -c /tmp/d34e.cookie -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode "password=$PW"
curl -s -b /tmp/d34e.cookie -c /tmp/d34e.cookie -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN /tmp/d34e.cookie | awk '{print $NF}')
echo "=== impact 快捷问（修复验证） ==="
curl -s -b /tmp/d34e.cookie -X POST "http://127.0.0.1:8080/api/v1/incidents/$RID_INC/diag" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d '{"key":"impact","createdBy":"human:operator"}'; echo
echo "=== 自由问（真模型） ==="
curl -s -b /tmp/d34e.cookie -X POST "http://127.0.0.1:8080/api/v1/incidents/$RID_INC/diag/free" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' --data-binary '{"question":"这次告警影响哪个服务？累计发生了多少次？","createdBy":"human:operator"}' | head -c 600; echo
echo "=== 最终统计 ==="
curl -s -b /tmp/d34e.cookie "http://127.0.0.1:8080/api/v1/diag/stats"; echo
