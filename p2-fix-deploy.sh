#!/bin/sh
# P2 修复批（V139 阶段词 + quote 转义）——OVERLAY 纪律
set -e
cd /opt/build/pr
echo '=== [1/6] md5 对拍 ==='
echo 'f79106ea1985e9040f0ecb2fd0e338a3  /tmp/p2-fix-batch.tar.gz' | md5sum -c -
echo '=== [2/6] OVERLAY 解包 ==='
tar xzf /tmp/p2-fix-batch.tar.gz
echo '=== [3/6] mvn package ==='
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3
echo '=== [4/6] compose build + migrate + up ==='
cd deploy
docker compose build control-app 2>&1 | tail -1
docker compose up migrate 2>&1 | tail -2
docker compose up -d control-app 2>&1 | tail -1
echo '=== [5/6] health + flyway ==='
sleep 40
for i in 1 2 3 4 5 6; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  echo "health attempt$i -> $code"
  [ "$code" = "200" ] && break
  sleep 15
done
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success from flyway_schema_history where success order by installed_rank desc limit 1;"
echo '=== [6/6] 约束核验 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select pg_get_constraintdef(oid) from pg_constraint where conname='ck_eval_phase_event_phase';"
echo '=== 旧 worker 遗留清理确认 ==='
docker ps --format '{{.Names}}' | grep -i eval
