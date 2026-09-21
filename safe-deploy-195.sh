#!/bin/sh
# PAGE ??195 ?????ocs/??????????????????????????????????20260914.md ??????
# ?????as-deploy.sh ?????env ?????? -> overlay ?????? --delete??env ?????#   -> mvn package -> compose build control-app+web -> up migrate -> up -d -> health+???
# ???????????AGE-10 ?????select ?????81 ????????igrate ?????no-op ?????set -e
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"

echo '=== [1/7] md5 ??? ==='
cd /tmp && tr -d '\r' < safe-batch.tar.md5 | md5sum -c - || { echo 'FATAL: md5 ??????'; exit 1; }
cd /opt/build/pr

echo '=== [2/7] deploy/.env ?????????????????==='
cp deploy/.env "/tmp/env-backup-page-$(date +%Y%m%dT%H%M%S)"
ls /tmp/env-backup-page-* | tail -1
BEFORE_LINES=$(wc -l < deploy/.env)

echo '=== [3/7] overlay ?????? --delete??==='
tar xf /tmp/safe-batch.tar -C /opt/build/pr
cmp -s deploy/.env "$(ls /tmp/env-backup-page-* | tail -1)" && echo '.env intact=OK' || { echo '.env DIFF!!'; exit 1; }
[ "$(wc -l < deploy/.env)" = "$BEFORE_LINES" ] || { echo '.env line count changed'; exit 1; }
ls control-app/src/main/java/com/objwww/pr/control/eval/application/ | grep EvalLaunchGate

echo '=== [4/7] mvn package??????????????????195 ?????? ==='
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3

echo '=== [5/7] compose build??ontrol-app + web??==='
cd deploy
docker compose build control-app 2>&1 | tail -2
docker compose build web 2>&1 | tail -2

echo '=== [6/7] migrate + up ==='
docker compose up migrate 2>&1 | tail -2
docker compose up -d control-app web 2>&1 | tail -2

echo '=== [7/7] health + ?????? + flyway ==='
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

echo '--- ??????/ERROR ??? ---'
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -c 'APPLICATION FAILED' || true
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -cE 'ERROR' || true
echo '--- ????????---'
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -E 'Started .*Application' | tail -1
echo '--- flyway ??? ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success from flyway_schema_history where success order by installed_rank desc limit 1"
echo '--- live jar ?????AGE ?????????????????---'
docker exec deploy-control-app-1 sh -c "unzip -o -q /app/app.jar 'BOOT-INF/classes/com/objwww/pr/control/eval/application/EvalLaunchGate*.class' -d /tmp/pgchk && ls /tmp/pgchk/BOOT-INF/classes/com/objwww/pr/control/eval/application/ | head -4" 2>/dev/null \
  || docker exec deploy-control-app-1 sh -c "ls /app 2>/dev/null; ls / | head" \
  || { echo 'FATAL: jar ?????????'; exit 1; }
echo '--- ?????????????????401??---'
curl -s -o /dev/null -w 'launch-capability unauth=%{http_code}\n' http://127.0.0.1:8080/api/eval/launch-capability
echo 'SAFE-DEPLOY-DONE'

