#!/bin/sh
set -e
J=/tmp/p315b.cookie
rm -f $J
echo '=== 1) GET /api/auth/csrf（新会话） ==='
curl -s -c $J -D /tmp/h1.txt -o /dev/null http://127.0.0.1:8080/api/auth/csrf
grep -i 'set-cookie' /tmp/h1.txt || echo '(无 Set-Cookie)'
TA=$(grep XSRF-TOKEN $J | tail -1 | awk '{print $NF}')
echo "tokenA=$TA (len ${#TA})"
echo '=== 2) POST /api/auth/login（带 tokenA） ==='
curl -s -b $J -c $J -D /tmp/h2.txt -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $TA" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#p315-0916'
grep -i 'set-cookie' /tmp/h2.txt || echo '(登录响应无 Set-Cookie)'
TB=$(grep XSRF-TOKEN $J | tail -1 | awk '{print $NF}')
echo "tokenB=$TB (len ${#TB})"
echo '=== 3) GET 认证面（验会话） ==='
curl -s -o /dev/null -w 'me=%{http_code}\n' -b $J http://127.0.0.1:8080/api/auth/me
echo '=== 4) POST action-items 带 tokenB（登录后轮换令牌） ==='
INC=$(curl -s -b $J 'http://127.0.0.1:8080/api/v1/postmortems' | grep -oE '"incidentId":"[0-9a-f-]+"' | head -1 | cut -d'"' -f4)
echo "INC=$INC"
curl -s -w ' [%{http_code}]\n' -X POST "http://127.0.0.1:8080/api/v1/postmortems/$INC/action-items" -b $J -c $J -H "X-XSRF-TOKEN: $TB" -H 'Content-Type: application/json' -d '{"title":"probe-tokenB","owner":"probe"}'
echo '=== 5) POST 带 tokenA（登录前旧令牌） ==='
curl -s -w ' [%{http_code}]\n' -X POST "http://127.0.0.1:8080/api/v1/postmortems/$INC/action-items" -b $J -c $J -H "X-XSRF-TOKEN: $TA" -H 'Content-Type: application/json' -d '{"title":"probe-tokenA","owner":"probe"}'
echo '=== 6) 现取新令牌再 POST ==='
curl -s -b $J -c $J -o /dev/null http://127.0.0.1:8080/api/auth/csrf
TC=$(grep XSRF-TOKEN $J | tail -1 | awk '{print $NF}')
echo "tokenC=$TC (len ${#TC})"
curl -s -w ' [%{http_code}]\n' -X POST "http://127.0.0.1:8080/api/v1/postmortems/$INC/action-items" -b $J -c $J -H "X-XSRF-TOKEN: $TC" -H 'Content-Type: application/json' -d '{"title":"probe-tokenC","owner":"probe"}'
echo '=== 7) 对照：tokenC 打 catalog/owner ==='
curl -s -o /dev/null -w '%{http_code}\n' -X POST "http://127.0.0.1:8080/api/v1/catalog/owner" -b $J -H "X-XSRF-TOKEN: $TC" -H 'Content-Type: application/json' -d '{"service":"probe-svc","owner":"probe"}'
