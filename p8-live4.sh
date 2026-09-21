#!/bin/sh
echo '--- eval_run:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'state='||state||' created='||to_char(created_at,'HH24:MI:SS') from eval_run where id='52f84916-58bf-4eb5-9f05-90cc4220eeec'"
echo '--- rca_run 最近 6:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select state||' '||to_char(created_at,'HH24:MI:SS') from rca_run order by created_at desc limit 6"
echo '--- SIGQUIT 线程转储:'
docker exec -i eval-worker-std sh -c "kill -3 1" 2>/dev/null
sleep 4
docker logs --since 2m eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '^\s*"(?!.*drill)' -P 2>/dev/null | head -30
echo '--- 原始线程名清单:'
docker logs --since 2m eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -oE '"[a-zA-Z0-9_.-]+" #[0-9]+' | sort -u | head -40
