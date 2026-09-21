#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---worker log tail---'
sed 's/\x1b\[[0-9;]*m//g' /tmp/p6-worker.log | tail -12 | cut -c1-200
echo '---eval_run latest---'
$PG "select id, state, display_name from eval_run order by created_at desc limit 3;" 2>&1
echo '---rca c6964e0a---'
$PG "select r.id, r.state, i.incident_key from rca_run r left join incident i on i.id=r.incident_id where r.id='c6964e0a-c9e1-47d9-8ca7-bde9d473cab3';"
