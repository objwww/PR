#!/bin/sh
# A/B 对照：完全相同的登录+CSRF 流程，分别 POST /diag 与 /diag/free
PW='Tmp#diag34-0916'
RID_INC=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select r.incident_id from rca_run r where r.state='SUCCEEDED' limit 1" | tr -d '[:space:]')
printf '{"key":"impact","createdBy":"human:operator"}' > /tmp/d34-req1.json
printf '{"question":"test impact","createdBy":"human:operator"}' > /tmp/d34-req2.json

flow() {
  rm -f /tmp/d34b.cookie
  curl -s -c /tmp/d34b.cookie -o /dev/null http://127.0.0.1:8080/api/auth/csrf
  T=$(grep XSRF-TOKEN /tmp/d34b.cookie | awk '{print $NF}')
  curl -s -b /tmp/d34b.cookie -c /tmp/d34b.cookie -o /dev/null -w "login=%{http_code} " -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode "password=$PW"
  curl -s -b /tmp/d34b.cookie -c /tmp/d34b.cookie -o /dev/null http://127.0.0.1:8080/api/auth/csrf
  T=$(grep XSRF-TOKEN /tmp/d34b.cookie | awk '{print $NF}')
}
echo '=== A: POST /diag ==='
flow
curl -s -b /tmp/d34b.cookie -o /tmp/d34a.out -w 'diag=%{http_code}\n' -X POST "http://127.0.0.1:8080/api/v1/incidents/$RID_INC/diag" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' --data-binary @/tmp/d34-req1.json
head -c 150 /tmp/d34a.out; echo
echo '=== B: POST /diag/free ==='
flow
curl -s -b /tmp/d34b.cookie -o /tmp/d34b2.out -w 'free=%{http_code}\n' -X POST "http://127.0.0.1:8080/api/v1/incidents/$RID_INC/diag/free" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' --data-binary @/tmp/d34-req2.json
head -c 300 /tmp/d34b2.out; echo
echo '=== 响应头对照（B） ==='
flow
curl -s -b /tmp/d34b.cookie -D - -o /dev/null -X POST "http://127.0.0.1:8080/api/v1/incidents/$RID_INC/diag/free" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' --data-binary @/tmp/d34-req2.json | grep -iE 'HTTP/|set-cookie|allow|vary' | head -6
