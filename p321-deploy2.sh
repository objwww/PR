#!/bin/sh
set -e
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
echo '=== 1) 源码在场 ==='
ls control-app/src/main/java/com/objwww/pr/control/alert/interfaces/RoutingOverviewController.java
grep -c '调查路由' alert-web/src/views/ConfigCenterView.vue
echo '=== 2) mvn package ==='
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -2
echo '=== 4) up（镜像已在上次失败前构建？重建确保） ==='
cd deploy
docker compose build control-app web 2>&1 | tail -1
docker compose up -d control-app web 2>&1 | tail -2
sleep 40
i=1
while [ $i -le 8 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 10; i=$((i+1))
done
echo "health=$code"
J=/tmp/p321.cookie
rm -f $J
curl -s -c $J -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN $J | tail -1 | awk '{print $NF}')
curl -s -b $J -c $J -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Demo#0917'
echo '=== 烟测：routing/overview ==='
curl -s -b $J "http://127.0.0.1:8080/api/v1/routing/overview" | head -c 700; echo
echo '=== web chunk 复核 ==='
docker exec deploy-web-1 sh -c "grep -l '调查路由' /usr/share/nginx/html/assets/ConfigCenterView-*.js" || echo 'chunk 未含'
docker logs deploy-control-app-1 --since 3m 2>&1 | grep -c 'APPLICATION FAILED' || true
