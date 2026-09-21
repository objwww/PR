#!/bin/sh
# diag34 写路径复验：登录后重新取 CSRF（登录会轮换 cookie）
PW='Tmp#diag34-0916'
rm -f /tmp/d34.cookie
curl -s -c /tmp/d34.cookie -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T1=$(grep XSRF-TOKEN /tmp/d34.cookie | awk '{print $NF}')
curl -s -b /tmp/d34.cookie -c /tmp/d34.cookie -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T1" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#diag34-0916'
# 登录后重取（cookie 可能已被轮换）
curl -s -b /tmp/d34.cookie -c /tmp/d34.cookie -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN /tmp/d34.cookie | awk '{print $NF}')
echo "token_len=${#T}"
RID_INC=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select r.incident_id from rca_run r where r.state='SUCCEEDED' limit 1" | tr -d '[:space:]')
echo "sample_incident=$RID_INC"
echo '=== 快捷问（写路径） ==='
curl -s -b /tmp/d34.cookie -X POST "http://127.0.0.1:8080/api/v1/incidents/$RID_INC/diag" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d '{"key":"history","createdBy":"human:operator"}'; echo
echo '=== history 新字段透出 ==='
curl -s -b /tmp/d34.cookie "http://127.0.0.1:8080/api/v1/incidents/$RID_INC/diag" | head -c 420; echo
echo '=== 自由问（真模型，60s 预算） ==='
curl -s -b /tmp/d34.cookie -X POST "http://127.0.0.1:8080/api/v1/incidents/$RID_INC/diag/free" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d '{"question":"这次告警影响哪个服务？累计发生了多少次？","createdBy":"human:operator"}' | head -c 700; echo
echo '=== 最终统计 ==='
curl -s -b /tmp/d34.cookie "http://127.0.0.1:8080/api/v1/diag/stats"; echo
