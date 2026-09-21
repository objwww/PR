#!/bin/sh
# 排查 /api/rca-runs/{id} 的 claims 键（临时凭据已在位，不回显）
set -e
U=$(grep '^AUTH_OPERATOR_USERNAME=' /opt/build/pr/deploy/.env | cut -d= -f2- | tr -d '\r"')
J=/tmp/probe.jar
rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#wave3-20260916" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
echo '=== run detail 顶层键 ==='
curl -s -b $J http://127.0.0.1:8080/api/rca-runs/82cf4cbf-44fd-4ea0-9184-84115a53e9f0 \
  | python3 -c "import json,sys;d=json.load(sys.stdin);print(list(d.keys()));print('claims=',json.dumps(d.get('claims'),ensure_ascii=False)[:400])"
