#!/bin/sh
# 干净复验：登录 → 每次写请求前现取 CSRF
PW='Tmp#diag34-0916'
RID_INC=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select r.incident_id from rca_run r where r.state='SUCCEEDED' limit 1" | tr -d '[:space:]')
NEWTOKEN() {
  curl -s -b /tmp/d34.cookie -c /tmp/d34.cookie -o /dev/null http://127.0.0.1:8080/api/auth/csrf
  grep XSRF-TOKEN /tmp/d34.cookie | tail -1 | awk '{print $NF}'
}
rm -f /tmp/d34.cookie
NEWTOKEN >/dev/null
T=$(NEWTOKEN)
curl -s -b /tmp/d34.cookie -c /tmp/d34.cookie -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#diag34-0916'
printf '{"key":"impact","createdBy":"human:operator"}' > /tmp/d34-req1.json
printf '{"question":"\xe8\xbf\x99\xe6\xac\xa1\xe5\x91\x8a\xe8\xad\xa6\xe5\xbd\xb1\xe5\x93\x8d\xe5\x93\xaa\xe4\xb8\xaa\xe6\x9c\x8d\xe5\x8a\xa1\xef\xbc\x9f\xe7\xb4\xaf\xe8\xae\xa1\xe5\x8f\x91\xe7\x94\x9f\xe4\xba\x86\xe5\xa4\x9a\xe5\xb0\x91\xe6\xac\xa1\xef\xbc\x9f","createdBy":"human:operator"}' > /tmp/d34-req2.json
T=$(NEWTOKEN)
echo "=== 快捷问（token_len=${#T}） ==="
curl -s -b /tmp/d34.cookie -o /tmp/d34-r1.out -w 'diag=%{http_code}\n' -X POST "http://127.0.0.1:8080/api/v1/incidents/$RID_INC/diag" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' --data-binary @/tmp/d34-req1.json
head -c 200 /tmp/d34-r1.out; echo
T=$(NEWTOKEN)
echo "=== 自由问（token_len=${#T}） ==="
curl -s -b /tmp/d34.cookie -o /tmp/d34-r2.out -w 'free=%{http_code}\n' -X POST "http://127.0.0.1:8080/api/v1/incidents/$RID_INC/diag/free" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' --data-binary @/tmp/d34-req2.json
head -c 700 /tmp/d34-r2.out; echo
echo '=== 最终统计 ==='
curl -s -b /tmp/d34.cookie "http://127.0.0.1:8080/api/v1/diag/stats"; echo
