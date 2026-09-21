#!/bin/sh
echo '--- eval_run 状态:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'run='||state from eval_run where id='c61dab5a-8ddb-4ca5-9ba7-6310bcceafc5'"
echo '--- worker 最近日志:'
docker logs --since 12m eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -vE '^\s*$' | grep -v -i drill | tail -15
echo '--- worker 活着吗:'
docker ps --format '{{.Names}} {{.Status}}' | grep eval-worker
