#!/bin/sh
# trace17 截图临时口令轮换（沿 shot-swap.sh 先例：备份 → 轮换；截图完成后由 trace17-restore.sh 恢复）
set -e
PW='Tmp#trace17-0916'
echo '=== 前置检查：生成器在位 ==='
ls -la /tmp/Gen.java /tmp/ssc.jar 2>/dev/null || { echo 'FATAL: /tmp 生成器缺失'; exit 1; }
cd /opt/build/pr/deploy
BK=/tmp/env-backup-tr17shot-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"; echo "backup=$BK"
[ "$(md5sum < .env | cut -d' ' -f1)" = "$(md5sum < "$BK" | cut -d' ' -f1)" ] && echo 'backup md5=OK'
echo "$BK" > /tmp/tr17shot-bk-path
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java "$PW")
echo "hash_len=${#NEWHASH}"
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
grep -c '^AUTH_OPERATOR_PASSWORD_BCRYPT=' .env
docker compose up -d control-app >/dev/null
sleep 35
i=1
while [ $i -le 6 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 10; i=$((i+1))
done
echo "health=$code"
echo '=== 新口令登录自证 ==='
rm -f /tmp/t17.cookie
TOKEN=$(curl -s -c /tmp/t17.cookie http://127.0.0.1:8080/api/auth/csrf | sed 's/.*"token":"\([^"]*\)".*/\1/')
curl -s -b /tmp/t17.cookie -c /tmp/t17.cookie -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#trace17-0916'
curl -s -b /tmp/t17.cookie -o /dev/null -w 'me=%{http_code}\n' http://127.0.0.1:8080/api/auth/me
RID=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select id from rca_run where state='SUCCEEDED' limit 1" | tr -d '[:space:]')
echo "sample_run=$RID"
curl -s -b /tmp/t17.cookie -o /tmp/t17-trace.json -w 'trace=%{http_code}\n' "http://127.0.0.1:8080/api/rca-runs/$RID/trace"
head -c 500 /tmp/t17-trace.json; echo
