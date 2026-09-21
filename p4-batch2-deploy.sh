#!/bin/sh
set -e
cd /opt/build/pr
echo 'b1e9fdfb9456aa4c185ef6c2ad9723dc  /tmp/p4-batch2.tar.gz' | md5sum -c -
tar xzf /tmp/p4-batch2.tar.gz
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3
cd deploy
docker compose build control-app 2>&1 | tail -1
docker compose up -d control-app 2>&1 | tail -1
sleep 35
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo '--- V142 seed check ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select dv.name||':'||dv.version||' n='||count(cv.id) from dataset_version dv left join case_version cv on cv.dataset_version_id=dv.id and cv.valid_to is null where dv.partition_class='REDTEAM' group by dv.name, dv.version;"
echo '--- restart worker (rt-v2, rounds=1) ---'
docker rm -f eval-worker-p2replay >/dev/null 2>&1 || true
sh /tmp/p4w.sh
