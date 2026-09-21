#!/bin/sh
set -e
U=$(grep '^AUTH_OPERATOR_USERNAME=' /opt/build/pr/deploy/.env | cut -d= -f2- | tr -d '\r"')
J=/tmp/probe3.jar
rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#wave3-20260916" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
T2=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
echo "T1==T2? $(awk '$6=="XSRF-TOKEN"{print $7}' $J | md5sum | cut -c1-8) (jar 现值 md5 前8)"
echo '=== A. GET 同面（期待 200） ==='
curl -s -b $J -o /dev/null -w 'get-feedback=%{http_code}\n' \
  http://127.0.0.1:8080/api/v1/incidents/5781021b-735a-44ca-8281-d2ccf3de6c0c/conclusion-feedback
echo '=== B. POST rca-runs commands（OPERATOR 写面探针） ==='
T3=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -H "X-XSRF-TOKEN: $T3" -H 'Content-Type: application/json' \
  -d '{}' -w '\npost-commands=%{http_code}\n' \
  http://127.0.0.1:8080/api/rca-runs/82cf4cbf-44fd-4ea0-9184-84115a53e9f0/commands
echo '=== C. POST feedback 带 XSRF（重跑） ==='
T4=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -H "X-XSRF-TOKEN: $T4" -H 'Content-Type: application/json' \
  -d '{"verdict":"REJECTED","actor":"human:probe3","reason":"probe3","category":"OTHER"}' \
  -w '\npost-feedback=%{http_code}\n' \
  http://127.0.0.1:8080/api/v1/incidents/5781021b-735a-44ca-8281-d2ccf3de6c0c/conclusion-feedback
