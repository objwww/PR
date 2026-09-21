#!/bin/sh
set -e
cd /opt/build/pr/deploy
J=/tmp/probe19.jar; rm -f $J
U=$(grep '^AUTH_OPERATOR_USERNAME=' .env | cut -d= -f2- | tr -d '\r"')
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#ev09v-20260917" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
curl -s -b $J -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
echo '--- raw /api/eval/runs head ---'
curl -s -b $J 'http://127.0.0.1:8080/api/eval/runs?limit=2' | cut -c1-600
echo
echo '--- http code ---'
curl -s -o /dev/null -b $J -w 'code=%{http_code}\n' 'http://127.0.0.1:8080/api/eval/runs?limit=2'
