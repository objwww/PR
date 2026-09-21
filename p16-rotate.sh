#!/bin/sh
# p16 截图临时口令轮换（沿 shot-swap 先例；p16-audit.sh 收口恢复）
set -e
PW='Tmp#p16-0916'
cd /opt/build/pr/deploy
BK=/tmp/env-backup-p16shot-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"; echo "$BK" > /tmp/p16shot-bk-path
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java "$PW")
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 30
i=1
while [ $i -le 6 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 10; i=$((i+1))
done
echo "health=$code rotated=OK"
