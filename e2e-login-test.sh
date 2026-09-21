#!/bin/sh
rm -f /tmp/e2e-cookie.txt
TOKEN=$(curl -s -c /tmp/e2e-cookie.txt http://127.0.0.1:8080/api/auth/csrf | sed 's/.*"token":"\([^"]*\)".*/\1/')
echo "csrf_len=${#TOKEN}"
curl -s -b /tmp/e2e-cookie.txt -c /tmp/e2e-cookie.txt -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=operator'
curl -s -b /tmp/e2e-cookie.txt -o /dev/null -w 'me=%{http_code}\n' http://127.0.0.1:8080/api/auth/me
