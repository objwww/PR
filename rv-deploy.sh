#!/bin/sh
# RV 批部署（RV01~RV08+RV11 代码批 + T20/T21 IT 资产）——OVERLAY 纪律
set -e
cd /opt/build/pr
echo '=== [1/7] md5 对拍 ==='
echo '0f2f7e0a72f5bcf7a8885482b4c0dd97  /tmp/rv-batch.tar.gz' | md5sum -c -
echo '=== [2/7] deploy/.env 保险备份（内容不回显） ==='
cp deploy/.env /tmp/env-backup-rv-$(date +%Y%m%dT%H%M%S)
ls /tmp/env-backup-rv-* | tail -1
echo '=== [3/7] OVERLAY 解包（无 --delete） ==='
tar xzf /tmp/rv-batch.tar.gz
cmp -s deploy/.env "$(ls /tmp/env-backup-rv-* | tail -1)" && echo '.env intact=OK' || { echo '.env DIFF!!'; exit 1; }
echo '=== [4/7] mvn package ==='
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3
echo '=== [5/7] compose build (control-app + web) ==='
cd deploy
docker compose build control-app 2>&1 | tail -2
docker compose build web 2>&1 | tail -2
echo '=== [6/7] migrate + up ==='
docker compose up migrate 2>&1 | tail -2
docker compose up -d control-app web 2>&1 | tail -2
echo '=== [7/7] health + 装配面核验 ==='
sleep 40
for i in 1 2 3 4 5 6; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  echo "health attempt$i -> $code"
  [ "$code" = "200" ] && break
  sleep 15
done
curl -s -o /dev/null -w 'web8090=%{http_code}\n' http://127.0.0.1:8090/ || true
echo '--- 启动失败/ERROR 计数 ---'
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -c 'APPLICATION FAILED' || true
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -cE ' ERROR ' || true
echo '--- 启动完成行 ---'
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -E 'Started .*Application' | tail -1
echo '--- flyway 顶端 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success from flyway_schema_history where success order by installed_rank desc limit 1;"
echo '--- 新类面核验（exec.jar 内 RV 批指纹） ---'
cd /opt/build/pr
JAR=$(ls control-app/target/*.jar 2>/dev/null | grep -v '\.original' | head -1)
echo "jar=$JAR"
JH=/opt/jdk-21.0.12.1+1/bin
$JH/javap -p -constants -cp "$JAR" 'com.objwww.pr.control.alert.application.tool.InFlightToolCancels$Handle' 2>&1 | grep -o 'CANCELLED_BEFORE_START' | head -1
$JH/javap -cp "$JAR" com.objwww.pr.control.alert.domain.repository.DelegationReceiptRepository 2>&1 | grep -o 'insertIfAbsent' | head -1
$JH/javap -cp "$JAR" 'com.objwww.pr.control.alert.application.agent.RoleDriveResult$ChildResult' 2>&1 | grep -o 'class com.objwww.pr.control.alert.application.agent.RoleDriveResult\$ChildResult' | head -1
$JH/javap -p -constants -cp "$JAR" com.objwww.pr.notify.domain.service.FencedNotifyExecutor 2>&1 | grep -o 'MAX_DETAIL_CHARS' | head -1
$JH/javap -c -cp "$JAR" com.objwww.pr.notify.domain.service.FencedNotifyExecutor 2>&1 | grep -c 'channel_not_configured'
echo '=== 容器态 ==='
docker ps --format '{{.Names}} {{.Status}}' | grep deploy
echo DEPLOY-DONE
