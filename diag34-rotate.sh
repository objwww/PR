#!/bin/sh
# diag34 截图临时口令轮换（沿 shot-swap 先例；验收后由 diag34-audit.sh 恢复）+ 端到端问答链验证
set -e
PW='Tmp#diag34-0916'
cd /opt/build/pr/deploy
BK=/tmp/env-backup-diag34shot-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"; echo "backup=$BK"
echo "$BK" > /tmp/diag34shot-bk-path
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
echo "health=$code"
rm -f /tmp/d34.cookie
curl -s -c /tmp/d34.cookie -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN /tmp/d34.cookie | awk '{print $NF}')
curl -s -b /tmp/d34.cookie -c /tmp/d34.cookie -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#diag34-0916'
echo '=== 统计端点（登录后） ==='
curl -s -b /tmp/d34.cookie "http://127.0.0.1:8080/api/v1/diag/stats"; echo
echo '=== 选一个有调查记录的事件做快捷问（写路径验证） ==='
RID_INC=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select r.incident_id from rca_run r where r.state='SUCCEEDED' limit 1" | tr -d '[:space:]')
echo "sample_incident=$RID_INC"
curl -s -b /tmp/d34.cookie -X POST "http://127.0.0.1:8080/api/v1/incidents/$RID_INC/diag" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d '{"key":"history","createdBy":"human:operator"}'; echo
echo '=== history 新字段（question_key/answer_refs 透出验证） ==='
curl -s -b /tmp/d34.cookie "http://127.0.0.1:8080/api/v1/incidents/$RID_INC/diag" | head -c 500; echo
echo '=== 自由问（真模型链路，60s 预算） ==='
curl -s -b /tmp/d34.cookie -X POST "http://127.0.0.1:8080/api/v1/incidents/$RID_INC/diag/free" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d '{"question":"这次告警影响哪个服务？持续了多久？","createdBy":"human:operator"}' | head -c 600; echo
echo '=== 再次统计（应 +2） ==='
curl -s -b /tmp/d34.cookie "http://127.0.0.1:8080/api/v1/diag/stats"; echo
