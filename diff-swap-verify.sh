#!/bin/sh
set -e
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java 'Tmp#diff-20260916')
cd /opt/build/pr/deploy
BK=/tmp/env-backup-diff-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"; echo "backup=$BK"
[ "$(md5sum < .env | cut -d' ' -f1)" = "$(md5sum < "$BK" | cut -d' ' -f1)" ] && echo 'backup md5=OK'
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 35
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo "$BK" > /tmp/diff-bk-path
U=$(grep '^AUTH_OPERATOR_USERNAME=' .env | cut -d= -f2- | tr -d '\r"')
J=/tmp/probe7.jar; rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#diff-20260916" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
echo '=== active-digest ==='
curl -s -b $J http://127.0.0.1:8080/api/v1/config-bundles/active-digest
echo
AD=$(curl -s -b $J http://127.0.0.1:8080/api/v1/config-bundles/active-digest | python3 -c "import json,sys;print(json.load(sys.stdin)['items'][0]['digest'])")
echo '=== 自 diff（期待全空） ==='
curl -s -b $J "http://127.0.0.1:8080/api/v1/config-bundles/$AD/diff/$AD" | python3 -c "import json,sys;d=json.load(sys.stdin);print('status=',d['status'],'changed=',len(d['changed']),'added=',len(d['added']),'removed=',len(d['removed']),'unchanged=',d['unchangedCount'])"
