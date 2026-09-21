#!/bin/sh
set -e
cd /opt/build/pr
echo '00f0834749679104168feb2386ea3b0f  /tmp/p6-batch.tar.gz' | md5sum -c -
tar xzf /tmp/p6-batch.tar.gz
cd deploy
docker compose up -d control-app 2>&1 | tail -1
sleep 35
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo '--- V143/V144 生效核对 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select column_name from information_schema.columns where table_name='eval_case_result' and column_name='difficulty';"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select dv.version||' '||cv.case_key||' d='||coalesce(cv.payload#>'{rawArtifact,gt_difficulty}','-')::text||' p='||coalesce(cv.payload#>'{rawArtifact,gt_panel}','-')::text from case_version cv join dataset_version dv on dv.id=cv.dataset_version_id where dv.name='redteam-ds' and cv.valid_to is null order by dv.version desc, cv.case_key;"
