#!/bin/sh
# RV 批确定性重建：孤儿清理（git 真值镜像删除）→ clean 重建 → 镜像换代证明 → live jar 指纹
set -e
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
JH=/opt/jdk-21.0.12.1+1/bin

echo '=== [1/8] 删除 6 个 git 已删孤儿（BA-137 overlay 纪律：显式镜像删除，绝无 --delete） ==='
for f in \
  control-app/src/main/java/com/objwww/pr/control/domain/ai/ModelRouteCatalog.java \
  control-app/src/test/java/com/objwww/pr/control/domain/ai/MockModelGateway.java \
  shared-kernel/src/main/java/com/objwww/pr/shared/snapshot/SafeTarExtractor.java \
  shared-kernel/src/main/java/com/objwww/pr/shared/snapshot/SecurityRejectionException.java \
  shared-kernel/src/main/java/com/objwww/pr/shared/snapshot/SnapshotTree.java \
  shared-kernel/src/test/java/com/objwww/pr/shared/snapshot/SafeTarExtractorTest.java ; do
  if [ -f "$f" ]; then rm "$f" && echo "removed: $f"; else echo "absent(skip): $f"; fi
done
rmdir shared-kernel/src/main/java/com/objwww/pr/shared/snapshot 2>/dev/null || true
rmdir shared-kernel/src/test/java/com/objwww/pr/shared/snapshot 2>/dev/null || true
rmdir control-app/src/main/java/com/objwww/pr/control/domain/ai 2>/dev/null || true
rmdir control-app/src/test/java/com/objwww/pr/control/domain/ai 2>/dev/null || true

echo '=== [2/8] 镜像换代前快照 ==='
docker inspect -f '{{.Id}} {{.Created}}' pr-agent/control-app:0.0.1-SNAPSHOT || true
docker inspect -f '{{.Id}} {{.Created}}' pr-agent/notify-app:0.0.1-SNAPSHOT || true

echo '=== [3/8] mvn clean package control-app -am（全量日志 /tmp/rv-build-ctl.log） ==='
mvn -B -ntp -pl control-app -am -DskipTests clean package > /tmp/rv-build-ctl.log 2>&1
grep -E '^\[INFO\] BUILD' /tmp/rv-build-ctl.log
ls -la control-app/target/control-app-0.0.1-SNAPSHOT-exec.jar

echo '=== [4/8] mvn clean package notify-app -am（/tmp/rv-build-ntf.log） ==='
mvn -B -ntp -pl notify-app -am -DskipTests clean package > /tmp/rv-build-ntf.log 2>&1
grep -E '^\[INFO\] BUILD' /tmp/rv-build-ntf.log
ls -la notify-app/target/notify-app-0.0.1-SNAPSHOT-exec.jar

echo '=== [5/8] compose build control-app + notify-app + web ==='
cd deploy
docker compose build control-app 2>&1 | tail -2
docker compose build notify-app 2>&1 | tail -2
docker compose build web 2>&1 | tail -2

echo '=== [6/8] 镜像换代证明（.Created 必须更新为今天） ==='
cd /opt/build/pr
docker inspect -f '{{.Id}} {{.Created}}' pr-agent/control-app:0.0.1-SNAPSHOT
docker inspect -f '{{.Id}} {{.Created}}' pr-agent/notify-app:0.0.1-SNAPSHOT

echo '=== [7/8] up -d + 健康 ==='
cd deploy
docker compose up migrate 2>&1 | tail -2
docker compose up -d control-app notify-app web 2>&1 | tail -3
sleep 40
for i in 1 2 3 4 5 6; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  echo "control health attempt$i -> $code"
  [ "$code" = "200" ] && break
  sleep 15
done
docker inspect -f 'notify health={{.State.Health.Status}}' deploy-notify-app-1 2>/dev/null || true
curl -s -o /dev/null -w 'web8090=%{http_code}\n' http://127.0.0.1:8090/ || true
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -c 'APPLICATION FAILED' || true
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -cE ' ERROR ' || true
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -E 'Started .*Application' | tail -1
docker logs deploy-notify-app-1 --since 5m 2>&1 | grep -E 'Started .*Application' | tail -1
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success from flyway_schema_history where success order by installed_rank desc limit 1;"
docker ps --format '{{.Names}} {{.Status}}' | grep deploy

echo '=== [8/8] LIVE 容器 jar 指纹（唯一有效验收面） ==='
docker cp deploy-control-app-1:/app/app.jar /tmp/live-ctl.jar
rm -rf /tmp/fpL && mkdir -p /tmp/fpL && cd /tmp/fpL
"$JH/jar" -xf /tmp/live-ctl.jar BOOT-INF/classes
CL=/tmp/fpL/BOOT-INF/classes
"$JH/javap" -p -constants -cp "$CL" 'com.objwww.pr.control.alert.application.tool.InFlightToolCancels$Handle$Phase' | grep -o CANCELLED_BEFORE_START
"$JH/javap" -cp "$CL" com.objwww.pr.control.alert.domain.repository.DelegationReceiptRepository | grep -o insertIfAbsent
"$JH/javap" -cp "$CL" 'com.objwww.pr.control.alert.application.agent.RoleDriveResult$ChildResult' | grep -o ChildResult
"$JH/javap" -cp "$CL" com.objwww.pr.control.alert.application.tool.ToolGateway | grep -c InFlightToolCancels
docker cp deploy-notify-app-1:/app/app.jar /tmp/live-ntf.jar
rm -rf /tmp/fpN && mkdir -p /tmp/fpN && cd /tmp/fpN
"$JH/jar" -xf /tmp/live-ntf.jar BOOT-INF/classes
NL=/tmp/fpN/BOOT-INF/classes
"$JH/javap" -p -constants -cp "$NL" com.objwww.pr.notify.domain.service.FencedNotifyExecutor | grep -o MAX_DETAIL_CHARS
"$JH/javap" -c -cp "$NL" com.objwww.pr.notify.domain.service.FencedNotifyExecutor | grep -c channel_not_configured
echo REBUILD-DONE
