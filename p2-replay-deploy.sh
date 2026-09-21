#!/bin/sh
# P2 执行集接通批部署（V138 迁移 + 回放执行链）——OVERLAY 纪律
set -e
cd /opt/build/pr
echo '=== [1/8] md5 对拍 ==='
echo '0447928e317173b0f50adad786b7af5c  /tmp/p2-replay-batch.tar.gz' | md5sum -c -
echo '=== [2/8] deploy/.env 保险备份（内容不回显）==='
cp deploy/.env /tmp/env-backup-p2r-$(date +%Y%m%dT%H%M%S)
ls /tmp/env-backup-p2r-* | tail -1
echo '=== [3/8] OVERLAY 解包（无 --delete）==='
tar xzf /tmp/p2-replay-batch.tar.gz
cmp -s deploy/.env "$(ls /tmp/env-backup-p2r-* | tail -1)" && echo '.env intact=OK' || echo '.env DIFF!!'
echo '=== [4/8] mvn package ==='
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3
echo '=== [5/8] compose build (control-app + web) ==='
cd deploy
docker compose build control-app 2>&1 | tail -2
echo '=== [6/8] migrate + up ==='
docker compose up migrate 2>&1 | tail -2
docker compose up -d control-app 2>&1 | tail -2
echo '=== [7/8] health ==='
sleep 40
for i in 1 2 3 4 5 6; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  echo "health attempt$i -> $code"
  [ "$code" = "200" ] && break
  sleep 15
done
echo '--- 启动失败/ERROR 计数 ---'
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -c 'APPLICATION FAILED' || true
echo '--- flyway 顶版 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success from flyway_schema_history where success order by installed_rank desc limit 1;"
echo '=== [8/8] V138 授权核验 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select grantee||'|'||privilege_type||'|'||table_name from information_schema.role_table_grants where table_name='alert_inbox' and grantee='eval_app';"
echo '--- 容器态 ---'
docker ps --format '{{.Names}} {{.Status}}' | grep deploy
