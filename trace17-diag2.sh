#!/bin/sh
rm -f /tmp/t17.cookie
curl -s -c /tmp/t17.cookie -o /dev/null http://127.0.0.1:8080/api/auth/csrf
RAW=$(grep XSRF-TOKEN /tmp/t17.cookie | awk '{print $NF}')
echo "cookie_token_len=${#RAW}"
echo '--- 用 cookie 原始 token 登录（临时口令） ---'
curl -s -b /tmp/t17.cookie -c /tmp/t17.cookie -o /tmp/t17-login.out -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $RAW" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#trace17-0916'
head -c 120 /tmp/t17-login.out; echo
echo '--- 会话验证 + trace 端点 ---'
curl -s -b /tmp/t17.cookie -o /dev/null -w 'me=%{http_code}\n' http://127.0.0.1:8080/api/auth/me
RID=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select id from rca_run where state='SUCCEEDED' limit 1" | tr -d '[:space:]')
echo "sample_run=$RID"
curl -s -b /tmp/t17.cookie -o /tmp/t17-trace.json -w 'trace=%{http_code}\n' "http://127.0.0.1:8080/api/rca-runs/$RID/trace"
head -c 700 /tmp/t17-trace.json; echo
