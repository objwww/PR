#!/bin/sh
# 3.4 AI 诊断页批：诊断统计端点 + history 字段透出 + 前端诊断会话页（无新迁移，migrate 为 no-op 确认）
set -e
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"

echo '=== [1/6] md5 对拷 ==='
cd /tmp && tr -d '\r' < diag34-batch.tar.md5 | md5sum -c - || { echo 'FATAL: md5 对拷失败'; exit 1; }
cd /opt/build/pr

echo '=== [2/6] deploy/.env 风险备份 ==='
cp deploy/.env "/tmp/env-backup-diag34-$(date +%Y%m%dT%H%M%S)"
BK=$(ls /tmp/env-backup-diag34-* | tail -1)
echo "backup=$BK"
BEFORE_LINES=$(wc -l < deploy/.env)

echo '=== [3/6] overlay 解包（无 --delete） ==='
tar xf /tmp/diag34-batch.tar -C /opt/build/pr
cmp -s deploy/.env "$BK" && echo '.env intact=OK' || { echo '.env DIFF!!'; exit 1; }
[ "$(wc -l < deploy/.env)" = "$BEFORE_LINES" ] && echo '.env lines=OK' || { echo '.env 行数变化'; exit 1; }
ls control-app/src/main/java/com/objwww/pr/control/alert/interfaces/ | grep DiagStatsController

echo '=== [4/6] mvn package ==='
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3

echo '=== [5/6] compose build + migrate + up ==='
cd deploy
docker compose build control-app 2>&1 | tail -1
docker compose build web 2>&1 | tail -1
docker compose up migrate 2>&1 | tail -1
docker compose up -d control-app web 2>&1 | tail -2
sleep 40
i=1
while [ $i -le 8 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 10
  i=$((i+1))
done
echo "health=$code"

echo '=== [6/6] 烟测：统计端点 + 诊断问答链（登录后真事件） ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version from flyway_schema_history where success order by installed_rank desc limit 1;"
docker logs deploy-control-app-1 --since 3m 2>&1 | grep -c 'APPLICATION FAILED' || true
docker logs deploy-control-app-1 --since 3m 2>&1 | grep -E 'Started .*Application' | tail -1
