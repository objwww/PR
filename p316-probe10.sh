#!/bin/sh
set -e
docker cp deploy-control-app-1:/app/app.jar /tmp/run.jar
cd /tmp
rm -rf jx && mkdir jx
cd jx
# 用 jar 内嵌 jimage？宿主机有 jdk：/opt/jdk-21.0.12.1+1/bin/jar
JAR=/opt/jdk-21.0.12.1+1/bin/jar
$JAR xf /tmp/run.jar BOOT-INF/classes/com/objwww/pr/control/infrastructure/persistence/PostgresAgentOpsReader.class
grep -a -o '::long\|::bigint' BOOT-INF/classes/com/objwww/pr/control/infrastructure/persistence/PostgresAgentOpsReader.class | sort | uniq -c
