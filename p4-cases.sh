#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---eval-ds-1 case list (key|family|partition|crafted?)---'
$PG "select cv.case_key||'|'||cv.scenario_family_id||'|'||cv.partition_class||'|'||(cv.payload::text like '%adversarial_payload_json%') from case_version cv join dataset_version dv on dv.id=cv.dataset_version_id where dv.name in ('eval-ds-1','redteam-ds') order by cv.case_key;"
