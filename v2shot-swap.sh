#!/bin/sh
set -e
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java 'Tmp#v2shot-20260916')
cd /opt/build/pr/deploy
BK=/tmp/env-backup-v2shot-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"; echo "backup=$BK"
[ "$(md5sum < .env | cut -d' ' -f1)" = "$(md5sum < "$BK" | cut -d' ' -f1)" ] && echo 'backup md5=OK'
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 35
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo "$BK" > /tmp/v2shot-bk-path
