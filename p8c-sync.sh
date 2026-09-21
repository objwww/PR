#!/bin/sh
# p8c：落日志版源码 + 重建镜像（不动在跑 worker）+ prune 悬空层回收磁盘
set -e
cd /opt/build/pr
echo '== [1/4] 落源码 =='
rm -rf /tmp/p8c-files && mkdir -p /tmp/p8c-files
tar xzf /tmp/p8c-batch.tar.gz -C /tmp/p8c-files
cp -f /tmp/p8c-files/control-app/src/main/java/com/objwww/pr/control/eval/application/SingleCaseScorer.java \
      control-app/src/main/java/com/objwww/pr/control/eval/application/SingleCaseScorer.java
cp -f /tmp/p8c-files/control-app/src/main/resources/db/migration/V157__p8_eval_app_read_fallback_sources.sql \
      control-app/src/main/resources/db/migration/V157__p8_eval_app_read_fallback_sources.sql
grep -c 'log.warn' control-app/src/main/java/com/objwww/pr/control/eval/application/SingleCaseScorer.java
echo '== [2/4] mvn package =='
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD SUCCESS|BUILD FAILURE|ERROR' | head -4
echo '== [3/4] docker build =='
cd deploy
docker compose build control-app 2>&1 | grep -E 'naming|ERROR' | head -3
echo '== [4/4] prune 悬空镜像层 =='
docker image prune -f 2>&1 | tail -1
df -h / | tail -1
echo P8C_SYNC_OK
