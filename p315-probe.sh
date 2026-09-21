#!/bin/sh
set -e
echo "xsrf_lines=$(grep -c XSRF /tmp/p315.cookie || true)"
T=$(grep XSRF-TOKEN /tmp/p315.cookie | tail -1 | awk '{print $NF}')
echo "token_len=${#T}"
INC=$(curl -s -b /tmp/p315.cookie 'http://127.0.0.1:8080/api/v1/postmortems' | grep -oE '"incidentId":"[0-9a-f-]+"' | head -1 | cut -d'"' -f4)
echo "INC=$INC"
echo '--- POST 无 CSRF 头:'
curl -s -o /dev/null -w '%{http_code}\n' -X POST "http://127.0.0.1:8080/api/v1/postmortems/$INC/action-items" -b /tmp/p315.cookie -H 'Content-Type: application/json' -d '{"title":"probe-no-csrf"}'
echo '--- POST 带 CSRF 头:'
curl -s -w ' [%{http_code}]\n' -X POST "http://127.0.0.1:8080/api/v1/postmortems/$INC/action-items" -b /tmp/p315.cookie -c /tmp/p315.cookie -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d '{"title":"probe-with-csrf","owner":"probe"}'
echo '--- GET 清单:'
curl -s -b /tmp/p315.cookie "http://127.0.0.1:8080/api/v1/postmortems/$INC/action-items"; echo
echo '--- 对照：catalog/owner 写面（3.13 已验收的 POST）:'
curl -s -o /dev/null -w '%{http_code}\n' -X POST "http://127.0.0.1:8080/api/v1/catalog/owner" -b /tmp/p315.cookie -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d '{"service":"probe-svc","owner":"probe"}'
