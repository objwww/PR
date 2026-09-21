#!/bin/sh
# p11: 持久化 operator 口令 = 前端预填的 Demo#0917（.env 固化，重启不丢、舞步轮换后恢复即它）
set -e
cd /opt/build/pr/deploy

echo '== [0/3] 清窗检查 =='
BUSY=0
A=$(docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select count(*) from arena.oa_chaos_session where state in ('ACTIVE','RECOVERING')")
E=$(docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select count(*) from eval_run where state in ('RUNNING','SCORING','PENDING')")
D=$(docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select count(*) from drill_job where state not in ('CLOSED','FAILED','DONE','RECOVERY_FAILED','CANCELLED')" 2>/dev/null || echo 0)
echo "chaos=$A eval=$E drill=$D"
[ "$A" = "0" ] && [ "$E" = "0" ] && [ "$D" = "0" ] || { echo WINDOW_BUSY; exit 8; }

echo '== [1/3] .env 备份并固化 Demo#0917 bcrypt =='
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java 'Demo#0917')
BK=/tmp/env-backup-p11-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"
echo "backup=$BK"
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
grep -c '^AUTH_OPERATOR_PASSWORD_BCRYPT=' .env

echo '== [2/3] 重启 control-app（env 生效）=='
docker compose up -d control-app >/dev/null
sleep 35
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health

echo '== [3/3] 预填口令登录验证 =='
J=/tmp/probe-p11.jar; rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -o /dev/null -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=operator" --data-urlencode "password=Demo#0917" \
  -w 'login_Demo0917=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
curl -s -o /dev/null -w 'front=%{http_code}\n' http://127.0.0.1:8090/
echo P11_OK
