#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
sleep 420
sh /tmp/p6plGA.sh
echo '---worker cmd args (judge)---'
docker inspect eval-worker-std --format '{{join .Config.Cmd " "}}' | tr ' ' '\n' | grep judge
