#!/bin/sh
echo '--- 最近 60 行非 drill 日志:'
docker logs --since 20m eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -v -i drill | grep -vE '^\s*$' | tail -25
echo '--- eval_run 行全貌:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -x -c \
  "select state, created_at, updated_at from eval_run where id='52f84916-58bf-4eb5-9f05-90cc4220eeec'" 2>&1
echo '--- rca_run 最近 8 条:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select state||' '||to_char(started_at,'HH24:MI:SS')||' '||coalesce(input_digest,'-') from rca_run order by started_at desc limit 8" 2>&1
echo '--- jstack 摘要（eval 相关线程栈顶）:'
docker exec -i eval-worker-std sh -c "kill -3 1" 2>/dev/null
sleep 2
docker logs --since 1m eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -A 6 -E '"eval-' | head -40
