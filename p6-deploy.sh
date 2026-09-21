#!/bin/sh
set -e
cd /opt/build/pr
echo '0fe0dc3d4108a296ac66dd2e4d4b87bd  /tmp/p6-batch.tar.gz' | md5sum -c -
tar xzf /tmp/p6-batch.tar.gz
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3
cd deploy
docker compose build control-app web 2>&1 | grep -cE 'DONE' 
docker compose up -d control-app web 2>&1 | tail -2
sleep 35
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo '--- V143/V144 生效核对 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select column_name from information_schema.columns where table_name='eval_case_result' and column_name='difficulty';"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select case_key||' d='||coalesce(payload#>'{rawArtifact,gt_difficulty}','-')::text||' p='||coalesce(payload#>'{rawArtifact,gt_panel}','-')::text from case_version cv join dataset_version dv on dv.id=cv.dataset_version_id where dv.name='redteam-ds' and dv.version='rt-v2' and cv.valid_to is null order by case_key;"
echo '--- 重启 worker（rt-v2+rounds=1）---'
docker rm -f eval-worker-p4rt >/dev/null 2>&1 || true
sh /tmp/p4w.sh
