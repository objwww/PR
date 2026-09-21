#!/bin/sh
echo '--- flyway 最新 5 条（确认无并行会话半成品迁移混入）:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select version||' '||description||' success='||success from flyway_schema_history order by installed_rank desc limit 5"
echo '--- control-app 健康:'
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo '--- 预填口令最终复验:'
J=/tmp/probe-p11b.jar; rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -o /dev/null -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=operator" --data-urlencode "password=Demo#0917" \
  -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
