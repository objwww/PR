#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---all dataset_version with case counts---'
$PG "select dv.name||'|'||dv.version||'|'||dv.partition_class||'|'||count(cv.id) from dataset_version dv left join case_version cv on cv.dataset_version_id=dv.id and now() >= cv.valid_from and (cv.valid_to is null or now() < cv.valid_to) group by dv.name, dv.version, dv.partition_class order by 1;"
