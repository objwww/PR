#!/bin/sh
# RV 批部署补段：notify-app 镜像重建（RV05/07）+ exec.jar 解包指纹 + web 资产核验
set -e
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
JH=/opt/jdk-21.0.12.1+1/bin
echo '=== [1/5] mvn package notify-app（-am 带 shared-kernel） ==='
mvn -B -ntp -pl notify-app -am -DskipTests package 2>&1 | grep -E 'BUILD|ERROR' | tail -3
echo '=== [2/5] compose build + up notify-app ==='
cd deploy
docker compose build notify-app 2>&1 | tail -2
docker compose up -d notify-app 2>&1 | tail -1
sleep 20
for i in 1 2 3 4; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8082/actuator/health || true)
  [ "$code" != "200" ] && code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8082/health || true)
  echo "notify health attempt$i -> $code"
  [ "$code" = "200" ] && break
  sleep 10
done
docker logs deploy-notify-app-1 --since 3m 2>&1 | grep -cE ' ERROR ' || true
docker logs deploy-notify-app-1 --since 3m 2>&1 | grep -E 'Started .*Application' | tail -1
echo '=== [3/5] web 静态资产核验（懒加载 chunk 面） ==='
docker exec deploy-web-1 sh -c 'ls /usr/share/nginx/html/assets 2>/dev/null | grep -c echarts || true'
docker exec deploy-web-1 sh -c 'ls /usr/share/nginx/html/assets 2>/dev/null | head -5'
echo '=== [4/5] control-app exec.jar 指纹（解 BOOT-INF/classes） ==='
cd /opt/build/pr
rm -rf /tmp/fp && mkdir -p /tmp/fp/ctl
cd /tmp/fp/ctl && $JH/jar -xf /opt/build/pr/control-app/target/control-app-0.0.1-SNAPSHOT-exec.jar BOOT-INF/classes && cd /
CL=/tmp/fp/ctl/BOOT-INF/classes
$JH/javap -p -constants -cp "$CL" 'com.objwww.pr.control.alert.application.tool.InFlightToolCancels$Handle' 2>&1 | grep -o 'CANCELLED_BEFORE_START' | head -1
$JH/javap -cp "$CL" com.objwww.pr.control.alert.domain.repository.DelegationReceiptRepository 2>&1 | grep -o 'insertIfAbsent' | head -1
$JH/javap -cp "$CL" 'com.objwww.pr.control.alert.application.agent.RoleDriveResult$ChildResult' 2>&1 | grep -o 'RoleDriveResult\$ChildResult' | head -1
$JH/javap -cp "$CL" com.objwww.pr.control.alert.application.tool.ToolGateway 2>&1 | grep -c 'InFlightToolCancels'
echo '=== [5/5] notify-app exec.jar 指纹 ==='
mkdir -p /tmp/fp/ntf && cd /tmp/fp/ntf
NJAR=$(ls /opt/build/pr/notify-app/target/notify-app-*-exec.jar | head -1)
$JH/jar -xf "$NJAR" BOOT-INF/classes
NL=/tmp/fp/ntf/BOOT-INF/classes
$JH/javap -p -constants -cp "$NL" com.objwww.pr.notify.domain.service.FencedNotifyExecutor 2>&1 | grep -o 'MAX_DETAIL_CHARS' | head -1
$JH/javap -c -cp "$NL" com.objwww.pr.notify.domain.service.FencedNotifyExecutor 2>&1 | grep -c 'channel_not_configured'
$JH/javap -c -cp "$NL" com.objwww.pr.duty.webhook.DutyWebhookClient 2>&1 | grep -c 'quote' || true
echo DEPLOY2-DONE
