#!/bin/sh
set -e
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
echo '=== 1) 源码 md5（期待 0cd27d9a...） ==='
md5sum control-app/src/main/java/com/objwww/pr/control/infrastructure/persistence/PostgresAgentOpsReader.java
echo '=== 2) mvn package ==='
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -2
echo '=== 3) target jar class 串验尸（期待仅 bigint） ==='
cd /tmp && rm -rf jx2 && mkdir jx2 && cd jx2
/opt/jdk-21.0.12.1+1/bin/jar xf /opt/build/pr/control-app/target/control-app-0.0.1-SNAPSHOT.jar BOOT-INF/classes/com/objwww/pr/control/infrastructure/persistence/PostgresAgentOpsReader.class
grep -a -o '::long\|::bigint' BOOT-INF/classes/com/objwww/pr/control/infrastructure/persistence/PostgresAgentOpsReader.class | sort | uniq -c
echo '=== 4) docker build ==='
cd /opt/build/pr/deploy
docker compose build control-app 2>&1 | tail -1
echo '=== 5) 新镜像 ID 与创建时间 ==='
docker image inspect pr-agent/control-app:0.0.1-SNAPSHOT --format '{{.Id}} {{.Created}}'
echo '=== 6) up -d ==='
docker compose up -d control-app 2>&1 | tail -3
sleep 40
i=1
while [ $i -le 8 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 10; i=$((i+1))
done
echo "health=$code"
echo '=== 7) 容器 class 复核 ==='
docker cp deploy-control-app-1:/app/app.jar /tmp/run2.jar >/dev/null
cd /tmp && rm -rf jx3 && mkdir jx3 && cd jx3
/opt/jdk-21.0.12.1+1/bin/jar xf /tmp/run2.jar BOOT-INF/classes/com/objwww/pr/control/infrastructure/persistence/PostgresAgentOpsReader.class
grep -a -o '::long\|::bigint' BOOT-INF/classes/com/objwww/pr/control/infrastructure/persistence/PostgresAgentOpsReader.class | sort | uniq -c
