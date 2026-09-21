#!/bin/sh
# PAGE 批 195 部署（docs/评测监控与故障演练页面-生产就绪复核与收口方案-20260914.md 修复批）
# 流程沿 as-deploy.sh 惯例：.env 风险备份 -> overlay 解包（无 --delete，.env 不动）
#   -> mvn package -> compose build control-app+web -> up migrate -> up -d -> health+指纹
# 本批无新迁移（PAGE-10 只新增 select 读面，V81 授权内），migrate 照跑为 no-op 确认。
set -e
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"

echo '=== [1/7] md5 对拷 ==='
cd /tmp && tr -d '\r' < page-batch.tar.md5 | md5sum -c - || { echo 'FATAL: md5 对拷失败'; exit 1; }
cd /opt/build/pr

echo '=== [2/7] deploy/.env 风险备份（内容不回显） ==='
cp deploy/.env "/tmp/env-backup-page-$(date +%Y%m%dT%H%M%S)"
ls /tmp/env-backup-page-* | tail -1
BEFORE_LINES=$(wc -l < deploy/.env)

echo '=== [3/7] overlay 解包（无 --delete） ==='
tar xf /tmp/page-batch.tar -C /opt/build/pr
cmp -s deploy/.env "$(ls /tmp/env-backup-page-* | tail -1)" && echo '.env intact=OK' || { echo '.env DIFF!!'; exit 1; }
[ "$(wc -l < deploy/.env)" = "$BEFORE_LINES" ] || { echo '.env line count changed'; exit 1; }
ls control-app/src/main/java/com/objwww/pr/control/eval/application/ | grep EvalLaunchGate

echo '=== [4/7] mvn package（本批已在本机全量回归；195 只构建） ==='
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3

echo '=== [5/7] compose build（control-app + web） ==='
cd deploy
docker compose build control-app 2>&1 | tail -2
docker compose build web 2>&1 | tail -2

echo '=== [6/7] migrate + up ==='
docker compose up migrate 2>&1 | tail -2
docker compose up -d control-app web 2>&1 | tail -2

echo '=== [7/7] health + 装配指纹 + flyway ==='
sleep 40
i=1
while [ $i -le 6 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  echo "health attempt$i -> $code"
  [ "$code" = "200" ] && break
  sleep 15
  i=$((i+1))
done
[ "$code" = "200" ] || { echo 'FATAL: health not 200'; docker logs deploy-control-app-1 --since 5m 2>&1 | tail -30; exit 1; }
curl -s -o /dev/null -w 'web8090=%{http_code}\n' http://127.0.0.1:8090/

echo '--- 启动失败/ERROR 计数 ---'
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -c 'APPLICATION FAILED' || true
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -cE 'ERROR' || true
echo '--- 启动完成迹 ---'
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -E 'Started .*Application' | tail -1
echo '--- flyway 顶层 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success from flyway_schema_history where success order by installed_rank desc limit 1"
echo '--- live jar 指纹：PAGE 批新类必须在运行容器内 ---'
docker exec deploy-control-app-1 sh -c "unzip -o -q /app/app.jar 'BOOT-INF/classes/com/objwww/pr/control/eval/application/EvalLaunchGate*.class' -d /tmp/pgchk && ls /tmp/pgchk/BOOT-INF/classes/com/objwww/pr/control/eval/application/ | head -4" 2>/dev/null \
  || docker exec deploy-control-app-1 sh -c "ls /app 2>/dev/null; ls / | head" \
  || { echo 'FATAL: jar 指纹核验失败'; exit 1; }
echo '--- 能力读面冒烟（未鉴权应 401） ---'
curl -s -o /dev/null -w 'launch-capability unauth=%{http_code}\n' http://127.0.0.1:8080/api/eval/launch-capability
echo 'PAGE-DEPLOY-DONE'
