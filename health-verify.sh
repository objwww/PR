#!/bin/sh
U=$(grep '^AUTH_OPERATOR_USERNAME=' /opt/build/pr/deploy/.env | cut -d= -f2- | tr -d '\r"')
J=/tmp/probe6.jar
rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#wave5-20260916" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
curl -s -b $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
curl -s -b $J http://127.0.0.1:8080/api/v1/system/health \
  | python3 -c "import json,sys;d=json.load(sys.stdin);print('status=',d['status']);[print(i['state'],'|',i['name'],'|',i['detail']) for i in d['items']]"
