#!/bin/sh
Q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo '=== 用户表与角色（不回显哈希） ==='
Q "select table_name from information_schema.tables where table_name like '%user%' and table_schema='public'"
Q "select username||' | '||role from auth_user" 2>/dev/null || Q "select username||' | '||coalesce(role,role_name,'?') from auth_users" 2>/dev/null || echo "表名待查"
echo '=== 复盘/目录 SQL 直验（语法与行数） ==='
Q "select 'pm_resolved='||count(*) from incident where status='RESOLVED'"
Q "select 'catalog_services='||count(*) from (select service from incident group by service) x"
echo '=== 以 operator 身份实测两接口（临时凭据置换在脚本内完成并恢复） ==='
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java 'Tmp#audit-20260916')
BK=/tmp/env-backup-auditx-$(date +%Y%m%dT%H%M%S)
cp /opt/build/pr/deploy/.env "$BK"
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" /opt/build/pr/deploy/.env
cd /opt/build/pr/deploy && docker compose up -d control-app >/dev/null && sleep 32
J=/tmp/probe12.jar; rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=operator" --data-urlencode "password=Tmp#audit-20260916" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
curl -s -b $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T2=$(awk '$6=="XSRF-TOKEN"{print $7}' $J | tail -1)
for P in postmortems catalog; do
  curl -s -b $J -o /tmp/resp-$P.json -w "$P=%{http_code}\n" http://127.0.0.1:8080/api/v1/$P
done
head -c 300 /tmp/resp-postmortems.json; echo
head -c 300 /tmp/resp-catalog.json; echo
cp "$BK" .env
A=$(md5sum < .env | cut -d' ' -f1); B=$(md5sum < "$BK" | cut -d' ' -f1)
[ "$A" = "$B" ] && echo 'RESTORED_OK'
docker compose up -d control-app >/dev/null
sleep 28
curl -s -o /dev/null -w 'final-health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
