#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---terminal---'
$PG "select state||' '||coalesce(terminal_reason,'-') from eval_run where id::text like 'da84b492%';"
echo '---worker status---'
docker ps -a --format '{{.Names}} {{.Status}}' | grep eval
echo '---worker log tail---'
docker logs eval-worker-std --tail 15 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | tail -8 | cut -c1-200
