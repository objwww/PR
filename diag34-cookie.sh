#!/bin/sh
PW='Tmp#diag34-0916'
rm -f /tmp/d34c.cookie
echo '=== 1) 首次 GET /csrf 的 Set-Cookie ==='
curl -s -c /tmp/d34c.cookie -D - -o /dev/null http://127.0.0.1:8080/api/auth/csrf | grep -i 'set-cookie'
echo '=== 2) cookie 罐内容 ==='
cat /tmp/d34c.cookie
T=$(grep XSRF-TOKEN /tmp/d34c.cookie | awk '{print $NF}')
echo '=== 3) 登录（看 Set-Cookie） ==='
curl -s -b /tmp/d34c.cookie -c /tmp/d34c.cookie -D - -o /dev/null -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode "password=$PW" | grep -iE 'HTTP/|set-cookie'
echo '=== 4) 登录后罐内容 ==='
cat /tmp/d34c.cookie
echo '=== 5) 再次 GET /csrf 的 Set-Cookie ==='
curl -s -b /tmp/d34c.cookie -c /tmp/d34c.cookie -D - -o /dev/null http://127.0.0.1:8080/api/auth/csrf | grep -i 'set-cookie'
echo '=== 6) 罐内容终态 ==='
cat /tmp/d34c.cookie
