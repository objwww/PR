#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---recent chaos sessions---'
$PG "select scenario_id||'|'||state||'|created='||created_at from arena.oa_chaos_session order by created_at desc limit 8;"
echo '---worker env run-tag---'
docker inspect eval-worker-std --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep -oE 'run-tag[^,]*' | head -1
echo '---worker cmd run-tag---'
docker inspect eval-worker-std --format '{{join .Config.Cmd " "}}' 2>/dev/null | grep -oE 'run-tag=[^ ]*' | head -1
