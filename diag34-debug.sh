#!/bin/sh
PW='Tmp#diag34-0916'
RID_INC=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select r.incident_id from rca_run r where r.state='SUCCEEDED' limit 1" | tr -d '[:space:]')
rm -f /tmp/d34d.cookie
curl -s -c /tmp/d34d.cookie -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN /tmp/d34d.cookie | awk '{print $NF}')
curl -s -b /tmp/d34d.cookie -c /tmp/d34d.cookie -o /dev/null -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode "password=$PW"
echo '=== 登录后罐 ==='
cat /tmp/d34d.cookie
curl -s -b /tmp/d34d.cookie -c /tmp/d34d.cookie -o /dev/null http://127.0.0.1:8080/api/auth/csrf
echo '=== 重取后罐 ==='
cat /tmp/d34d.cookie
T=$(grep XSRF-TOKEN /tmp/d34d.cookie | awk '{print $NF}')
echo "=== T=[$T] ==="
printf '{"question":"test impact","createdBy":"human:operator"}' > /tmp/d34-req2.json
curl -sv -b /tmp/d34d.cookie -o /tmp/d34d.out -X POST "http://127.0.0.1:8080/api/v1/incidents/$RID_INC/diag/free" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' --data-binary @/tmp/d34-req2.json 2>&1 | grep -iE 'cookie|> HTTP|< HTTP' | head -10
head -c 300 /tmp/d34d.out; echo
