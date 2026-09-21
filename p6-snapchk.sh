#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
sleep 100
echo '---launch_plan caseKeys（FUP-03 注入证明）---'
$PG "select launch_plan::text from eval_run where id::text like '12bcfacc%';" | head -c 700
echo ''
