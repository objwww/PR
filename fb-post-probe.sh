#!/bin/sh
# 直接验证 conclusion-feedback POST 端点（临时凭据在位，不回显）
set -e
U=$(grep '^AUTH_OPERATOR_USERNAME=' /opt/build/pr/deploy/.env | cut -d= -f2- | tr -d '\r"')
J=/tmp/probe2.jar
rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#wave3-20260916" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
T2=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
echo '=== POST conclusion-feedback ==='
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -d '{"verdict":"REJECTED","actor":"human:probe","reason":"curl-probe-test","category":"OTHER","actualCause":"probe-cause","runId":"82cf4cbf-44fd-4ea0-9184-84115a53e9f0"}' \
  -w '\nhttp=%{http_code}\n' \
  http://127.0.0.1:8080/api/v1/incidents/5781021b-735a-44ca-8281-d2ccf3de6c0c/conclusion-feedback
echo '=== 表内容 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select verdict||'|'||coalesce(category,'-')||'|'||coalesce(actual_cause,'-') from conclusion_feedback order by created_at desc limit 2"
