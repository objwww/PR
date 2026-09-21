#!/bin/sh
# CSRF/认证矩阵：operator 写面（新端点 vs 既有端点 POST /api/auth/users）
OB=$(docker exec deploy-control-app-1 env | grep '^APP_OPERATOR_API_BEARER=' | cut -d= -f2-)
BASE=http://127.0.0.1:8080
curl -s -c /tmp/op-csrf.jar -o /dev/null $BASE/api/auth/csrf
TOK=$(awk '$6=="XSRF-TOKEN"{print $7}' /tmp/op-csrf.jar)
echo "token-len=${#TOK}"; echo '--- cookie jar ---'; cat /tmp/op-csrf.jar
echo '--- A: bearer + csrf header + cookie (regression-candidates) ---'
curl -s -o /tmp/a.out -w '%{http_code}\n' -X POST -H "Authorization: Bearer $OB" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $TOK" -b /tmp/op-csrf.jar \
  -d '{"verdict":"ACCEPTED"}' $BASE/api/eval/regression-candidates
head -c 200 /tmp/a.out; echo
echo '--- B: bearer + csrf header only (no cookie) ---'
curl -s -o /tmp/b.out -w '%{http_code}\n' -X POST -H "Authorization: Bearer $OB" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $TOK" \
  -d '{"verdict":"ACCEPTED"}' $BASE/api/eval/regression-candidates
head -c 200 /tmp/b.out; echo
echo '--- C: no bearer + csrf cookie+header ---'
curl -s -o /tmp/c.out -w '%{http_code}\n' -X POST \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $TOK" -b /tmp/op-csrf.jar \
  -d '{"verdict":"ACCEPTED"}' $BASE/api/eval/regression-candidates
head -c 200 /tmp/c.out; echo
echo '--- D: 既有 operator 写面 POST /api/auth/users (bearer + csrf 全套) ---'
curl -s -o /tmp/d.out -w '%{http_code}\n' -X POST -H "Authorization: Bearer $OB" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $TOK" -b /tmp/op-csrf.jar \
  -d '{"username":"probe-x","password":"probe-x"}' $BASE/api/auth/users
head -c 200 /tmp/d.out; echo
echo '--- E: control-app 近 3 分钟 401/audit 相关日志 ---'
docker logs deploy-control-app-1 --since 3m 2>&1 | grep -iE 'unauthor|csrf|denied|audit' | tail -10
