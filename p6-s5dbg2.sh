#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---01:16:29 run incident linkage---'
$PG "select r.id, r.incident_id, i.incident_key, i.generation from rca_run r left join incident i on i.id=r.incident_id where r.id='33f61cab-980f-46af-ae08-4d4a7cde276f';"
echo '---cf1ac104 S5 phases---'
$PG "select phase, detail::text, created_at from eval_phase_event where eval_run_id::text like 'cf1ac104%' and detail::text like '%S5%' order by created_at;"
echo '---resolver shape---'
