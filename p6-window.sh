#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---latest named runs---'
$PG "select left(id::text,8)||' '||state||' '||coalesce(display_name,'?')||' '||created_at from eval_run where display_name is not null and display_name != '' order by created_at desc limit 3;"
echo '---workers---'
docker ps --format '{{.Names}} {{.Status}}' | grep eval
