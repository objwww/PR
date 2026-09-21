#!/bin/sh
# P4 红队最小集批部署（V141 种子 + SafetyGate 接线 + 对抗刺激驱动分支）——OVERLAY 纪律
set -e
cd /opt/build/pr
echo '=== [1/6] md5 对拍 ==='
echo '5e595e1f2106e25db3c34c0e8c6bb448  /tmp/p4-redteam-batch.tar.gz' | md5sum -c -
echo '=== [2/6] OVERLAY 解包 ==='
tar xzf /tmp/p4-redteam-batch.tar.gz
echo '=== [3/6] mvn package ==='
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3
echo '=== [4/6] compose build + migrate + up ==='
cd deploy
docker compose build control-app 2>&1 | tail -1
docker compose build web 2>&1 | tail -1
docker compose up migrate 2>&1 | tail -2
docker compose up -d control-app web 2>&1 | tail -2
echo '=== [5/6] health + flyway ==='
sleep 40
for i in 1 2 3 4 5 6; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  echo "health attempt$i -> $code"
  [ "$code" = "200" ] && break
  sleep 15
done
curl -s -o /dev/null -w 'web8090=%{http_code}\n' http://127.0.0.1:8090/
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success from flyway_schema_history where success order by installed_rank desc limit 1;"
echo '=== [6/6] 红队种子核验 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select dv.name||':'||dv.version||'|'||cv.case_key||'|'||dv.partition_class from case_version cv join dataset_version dv on dv.id=cv.dataset_version_id where dv.name='redteam-ds' order by cv.case_key;"
echo '--- 换 worker（crafted 刺激镜像）---'
docker rm -f eval-worker-p3replay >/dev/null 2>&1 || true
sh /tmp/p2w.sh
