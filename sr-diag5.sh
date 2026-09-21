#!/bin/sh
echo '=== control-app 容器状态 ==='
docker ps -a --filter name=deploy-control-app-1 --format '{{.Status}}'
docker inspect deploy-control-app-1 --format 'started={{.State.StartedAt}} image={{.Config.Image}}'
echo '=== 容器内 AUTH 值是否被插值破坏 ==='
docker exec deploy-control-app-1 sh -c 'env | grep AUTH_OPERATOR' || true
echo '=== target jar ==='
ls -la /opt/build/pr/control-app/target/*.jar 2>/dev/null || echo 'NO JAR'
echo '=== mvn 失败原因（重跑看尾部 30 行） ==='
cd /opt/build/pr && export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests > /tmp/mvn5.log 2>&1
tail -30 /tmp/mvn5.log
