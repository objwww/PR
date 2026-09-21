#!/bin/sh
rm -f /tmp/t17.cookie
echo '--- csrf 原文 ---'
curl -s -c /tmp/t17.cookie http://127.0.0.1:8080/api/auth/csrf
echo
TOKEN=$(curl -s -b /tmp/t17.cookie http://127.0.0.1:8080/api/auth/csrf | sed 's/.*"token":"\([^"]*\)".*/\1/')
echo "token_len=${#TOKEN}"
echo '--- 登录 operator/operator ---'
curl -s -b /tmp/t17.cookie -c /tmp/t17.cookie -o /tmp/t17-login1.out -w 'code=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=operator'
head -c 200 /tmp/t17-login1.out; echo
echo '--- 登录 operator/Tmp#shot-20260916 ---'
curl -s -b /tmp/t17.cookie -c /tmp/t17.cookie -o /tmp/t17-login2.out -w 'code=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#shot-20260916'
head -c 200 /tmp/t17-login2.out; echo
echo '--- .env 鉴权键名（不回显值） ---'
grep -oE '^AUTH_[A-Z_]+' /opt/build/pr/deploy/.env | head -10
