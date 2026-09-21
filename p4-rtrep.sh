#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---dba413b8 report---'
$PG "select left(raw_text,700) from rca_report where run_id='dba413b8-346e-4b53-9a9b-81125944431d';"
echo '---run meta---'
$PG "select state||' engine='||engine||' decision='||coalesce((select decision from canary_route_decision d where d.run_id=r.id order by created_at desc limit 1),'-') from rca_run r where id='dba413b8-346e-4b53-9a9b-81125944431d';"
