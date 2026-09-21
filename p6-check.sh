#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---rt-v3 rows---'
$PG "select dv.version, cv.case_key, cv.payload#>'{rawArtifact,gt_difficulty}', cv.payload#>'{rawArtifact,gt_panel}' from case_version cv join dataset_version dv on dv.id=cv.dataset_version_id where dv.name='redteam-ds' and cv.valid_to is null order by dv.version desc, cv.case_key;"
