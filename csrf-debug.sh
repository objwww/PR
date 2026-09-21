#!/bin/sh
U=$(grep '^AUTH_OPERATOR_USERNAME=' /opt/build/pr/deploy/.env | cut -d= -f2- | tr -d '\r"')
J=/tmp/probe4.jar
rm -f $J
echo '=== 1. GET /auth/csrf 后的 jar ==='
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
cat -A $J | sed 's/\t/[TAB]/g'
echo "=== csrf response headers ==="
curl -s -D - -o /dev/null http://127.0.0.1:8080/api/auth/csrf | grep -i 'set-cookie' | sed 's/=.*/=<redacted>/'
echo '=== 2. 登录后的 jar ==='
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
echo "T before login: [${T}]"
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#wave3-20260916" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
cat -A $J | sed 's/\t/[TAB]/g'
echo '=== 3. POST with token value from jar ==='
T2=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
echo "T2: [${T2}]"
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -d '{"verdict":"REJECTED","actor":"human:probe4","reason":"probe4","category":"OTHER"}' \
  -w '\npost=%{http_code}\n' \
  http://127.0.0.1:8080/api/v1/incidents/5781021b-735a-44ca-8281-d2ccf3de6c0c/conclusion-feedback
