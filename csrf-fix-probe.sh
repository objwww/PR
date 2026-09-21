#!/bin/sh
U=$(grep '^AUTH_OPERATOR_USERNAME=' /opt/build/pr/deploy/.env | cut -d= -f2- | tr -d '\r"')
J=/tmp/probe5.jar
rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#wave3-20260916" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
echo "=== 登录后再 GET /auth/csrf（authenticated） ==="
curl -s -b $J -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T2=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
echo "new token length: ${#T2}"
echo '=== 带新 token POST feedback ==='
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -d '{"verdict":"REJECTED","actor":"human:probe5","reason":"post-login-refresh-probe","category":"OTHER","actualCause":"probe5-cause"}' \
  -w '\npost=%{http_code}\n' \
  http://127.0.0.1:8080/api/v1/incidents/5781021b-735a-44ca-8281-d2ccf3de6c0c/conclusion-feedback
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select verdict||'|'||coalesce(category,'-')||'|'||coalesce(actual_cause,'-')||'|'||actor from conclusion_feedback order by created_at desc limit 2"
