#!/bin/sh
# 3.3 补齐部署：关联告警 + 合并时间轴（零迁移）+ 口令轮换 + 烟测（七步产物验尸）
set -e
PW='Tmp#p317-0917'
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
cd /tmp && tr -d '\r' < p317-batch.tar.md5 | md5sum -c -
cd /opt/build/pr
tar xf /tmp/p317-batch.tar -C /opt/build/pr
echo '=== 1) 源码在场核验 ==='
grep -c 'timeline-merged' control-app/src/main/java/com/objwww/pr/control/alert/interfaces/IncidentRelatedController.java
grep -c '关联告警' alert-web/src/views/IncidentDetailView.vue
echo '=== 2) mvn package ==='
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -2
echo '=== 3) target jar class 验尸（期待 IncidentRelatedController 在场） ==='
unzip -l control-app/target/control-app-0.0.1-SNAPSHOT.jar 'BOOT-INF/classes/com/objwww/pr/control/alert/interfaces/IncidentRelatedController.class' | grep -c IncidentRelatedController
echo '=== 4) docker build（control-app + web） ==='
cd deploy
docker compose build control-app web 2>&1 | tail -1
echo '=== 5) 新镜像 ID 与时间 ==='
docker image inspect pr-agent/control-app:0.0.1-SNAPSHOT --format '{{.Id}} {{.Created}}'
docker image inspect pr-agent/alert-web:0.0.1-SNAPSHOT --format '{{.Id}} {{.Created}}'
echo '=== 6) up -d ==='
docker compose up -d control-app web 2>&1 | tail -2
sleep 40
i=1
while [ $i -le 8 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 10; i=$((i+1))
done
echo "health=$code"
echo '=== 7) 容器 class 复核 ==='
docker cp deploy-control-app-1:/app/app.jar /tmp/run3.jar >/dev/null
cd /tmp && rm -rf jx4 && mkdir jx4 && cd jx4
/opt/jdk-21.0.12.1+1/bin/jar xf /tmp/run3.jar BOOT-INF/classes/com/objwww/pr/control/alert/interfaces/IncidentRelatedController.class && echo 'IncidentRelatedController.class 在场'
echo '=== web chunk 复核 ==='
docker exec deploy-web-1 sh -c "grep -l '关联告警' /usr/share/nginx/html/assets/IncidentDetailView-*.js" || echo 'chunk 未含'
echo '=== 口令轮换（截图用） ==='
SBK=/tmp/env-backup-p317shot-$(date +%Y%m%dT%H%M%S)
cp .env "$SBK"; echo "$SBK" > /tmp/p317shot-bk-path
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java "$PW")
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 30
J=/tmp/p317.cookie
rm -f $J
curl -s -c $J -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN $J | tail -1 | awk '{print $NF}')
curl -s -b $J -c $J -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#p317-0917'
echo '=== 烟测1：related ==='
INC=$(curl -s -b $J "http://127.0.0.1:8080/api/v1/incidents?limit=1" | grep -oE '"incidentId":"[0-9a-f-]+"' | head -1 | cut -d'"' -f4)
echo "INC=$INC"
curl -s -w ' [%{http_code}]\n' -b $J "http://127.0.0.1:8080/api/v1/incidents/$INC/related" | head -c 600
echo '=== 烟测2：timeline-merged ==='
curl -s -w ' [%{http_code}]\n' -b $J "http://127.0.0.1:8080/api/v1/incidents/$INC/timeline-merged" | head -c 900
docker logs deploy-control-app-1 --since 3m 2>&1 | grep -c 'APPLICATION FAILED' || true
