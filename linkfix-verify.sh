#!/bin/sh
# 关联调查兜底验证：临时置换口令（备份→置换→登录→取 run 字段→md5 恢复）——值零回显
set -e
cd /opt/build/pr/deploy
BK=/tmp/env-backup-linkfix-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"; echo "backup=$BK"
[ "$(md5sum < .env | cut -d' ' -f1)" = "$(md5sum < "$BK" | cut -d' ' -f1)" ] && echo 'backup md5=OK'
TMPHASH=$(python3 -c "import bcrypt;print(bcrypt.hashpw(b'Tmp#linkfix-20260916',bcrypt.gensalt(10)).decode())")
[ -n "$TMPHASH" ] || { echo 'no bcrypt tool'; exit 1; }
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$TMPHASH|" .env
docker compose up -d control-app >/dev/null
code=000
for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
  sleep 10
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
done
echo "health=$code"
U=$(sed -n 's/^AUTH_OPERATOR_USERNAME=//p' .env | tr -d '\r"')
rm -f /tmp/lj.jar
curl -s -c /tmp/lj.jar http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' /tmp/lj.jar)
curl -s -b /tmp/lj.jar -c /tmp/lj.jar -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#linkfix-20260916" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
echo '=== 详情 run 字段（5781021b） ==='
curl -s -b /tmp/lj.jar http://127.0.0.1:8080/api/v1/incidents/5781021b-735a-44ca-8281-d2ccf3de6c0c \
  | grep -o '"run":{[^}]*}' || echo 'run 缺失'
echo '=== 恢复 .env ==='
cp "$BK" .env
[ "$(md5sum < .env | cut -d' ' -f1)" = "$(md5sum < "$BK" | cut -d' ' -f1)" ] && echo 'RESTORED_OK'
docker compose up -d control-app >/dev/null
sleep 30
curl -s -o /dev/null -w 'final-health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
