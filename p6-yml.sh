#!/bin/sh
cd /tmp
rm -rf /tmp/jarx && mkdir -p /tmp/jarx
cd /tmp/jarx
JAR=$(ls /opt/build/pr/control-app/target/*.jar | head -1)
echo "JAR=$JAR"
/opt/jdk-21.0.12.1+1/bin/jar -xf "$JAR" BOOT-INF/classes/application-eval.yml BOOT-INF/classes/application.yml 2>&1
echo '---application-eval.yml---'
head -30 BOOT-INF/classes/application-eval.yml 2>/dev/null
echo '---application.yml head---'
head -20 BOOT-INF/classes/application.yml 2>/dev/null
