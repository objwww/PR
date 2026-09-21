#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---arena-replay-ds case payloads (key|rounds|alertname anchors)---'
$PG "select cv.case_key||'|'||cv.payload from case_version cv join dataset_version dv on dv.id=cv.dataset_version_id where dv.name='arena-replay-ds';"
