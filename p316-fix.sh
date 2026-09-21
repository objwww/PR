#!/bin/sh
# 3.16 修正补部署：costs SQL ::long→::bigint（PG 无 long 型——掩蔽 403 根因）
set -e
PW='Tmp#p316-0917'
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
echo '=== 构建树中本批 4 文件 md5（对照并行会话改动） ==='
md5sum control-app/src/main/java/com/objwww/pr/control/ops/domain/repository/AgentOpsReader.java \
       control-app/src/main/java/com/objwww/pr/control/ops/application/AgentOpsSummaryService.java \
       control-app/src/main/java/com/objwww/pr/control/ops/interfaces/AgentOpsController.java \
       control-app/src/main/java/com/objwww/pr/control/infrastructure/persistence/PostgresAgentOpsReader.java
cd /tmp && tr -d '\r' < p316-fix.tar.md5 | md5sum -c -
cd /opt/build/pr
tar xf /tmp/p316-fix.tar -C /opt/build/pr
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -2
cd deploy
docker compose build control-app 2>&1 | tail -1
docker compose up -d control-app 2>&1 | tail -1
sleep 40
i=1
while [ $i -le 8 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 10; i=$((i+1))
done
echo "health=$code"
SBK=/tmp/env-backup-p316fix-$(date +%Y%m%dT%H%M%S)
cp .env "$SBK"; echo "$SBK" > /tmp/p316shot-bk-path
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java "$PW")
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 30
J=/tmp/p316.cookie
rm -f $J
curl -s -c $J -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN $J | tail -1 | awk '{print $NF}')
curl -s -b $J -c $J -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#p316-0917'
echo '=== 烟测：costs（修复后） ==='
curl -s -w ' [%{http_code}]\n' -b $J "http://127.0.0.1:8080/api/agent-ops/costs"
echo '=== 回归：latency-layers / risk-events ==='
curl -s -o /dev/null -w 'latency=%{http_code}\n' -b $J "http://127.0.0.1:8080/api/agent-ops/latency-layers"
curl -s -o /dev/null -w 'risk=%{http_code}\n' -b $J "http://127.0.0.1:8080/api/agent-ops/risk-events"
