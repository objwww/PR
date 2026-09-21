#!/bin/sh
# EV-09 稳定性指标批部署（纯读面：无新迁移）——OVERLAY 纪律
set -e
cd /opt/build/pr
echo '=== [1/7] md5 对拍 ==='
echo 'c13da0884a25199aab196ba0b25994b2  /tmp/eval-ev09-batch.tar.gz' | md5sum -c -
echo '=== [2/7] deploy/.env 保险备份（内容不回显）==='
cp deploy/.env /tmp/env-backup-ev09-$(date +%Y%m%dT%H%M%S)
ls /tmp/env-backup-ev09-* | tail -1
echo '=== [3/7] OVERLAY 解包（无 --delete）==='
tar xzf /tmp/eval-ev09-batch.tar.gz
cmp -s deploy/.env "$(ls /tmp/env-backup-ev09-* | tail -1)" && echo '.env intact=OK' || echo '.env DIFF!!'
echo '=== [4/7] mvn package ==='
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3
echo '=== [5/7] compose build (control-app + web) ==='
cd deploy
docker compose build control-app 2>&1 | tail -2
docker compose build web 2>&1 | tail -2
echo '=== [6/7] up（无新迁移，migrate 幂等跑一遍）==='
docker compose up migrate 2>&1 | tail -2
docker compose up -d control-app web 2>&1 | tail -2
echo '=== [7/7] health + 核验 ==='
sleep 40
for i in 1 2 3 4 5 6; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  echo "health attempt$i -> $code"
  [ "$code" = "200" ] && break
  sleep 15
done
curl -s -o /dev/null -w 'web8090=%{http_code}\n' http://127.0.0.1:8090/
echo '--- 启动失败/ERROR 计数 ---'
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -c 'APPLICATION FAILED' || true
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -cE 'ERROR' || true
echo '--- 启动完成行 ---'
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -E 'Started .*Application' | tail -1
echo '--- flyway 顶版 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success from flyway_schema_history where success order by installed_rank desc limit 1;"
echo '--- 容器态 ---'
docker ps --format '{{.Names}} {{.Status}}' | grep deploy
