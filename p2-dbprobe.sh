#!/bin/sh
echo '--- dataset_version ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select name||'|'||version||'|'||source||'|'||source_class||'|'||partition_class from dataset_version order by created_at;"
echo '--- regression candidates ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select state||'|'||case_key||'|'||scenario_family_id from rca_regression_candidate;"
echo '--- case_version rows ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select dv.version||'|'||cv.case_key||'|'||left(cv.payload::text,160) from case_version cv join dataset_version dv on dv.id=cv.dataset_version_id order by dv.created_at, cv.case_key;"
echo '--- eval dataset-version env in main stack ---'
docker exec deploy-control-app-1 env 2>/dev/null | grep -i 'EVAL\|LAUNCH' || echo '(no eval env)'
echo '--- launch gate API capability ---'
