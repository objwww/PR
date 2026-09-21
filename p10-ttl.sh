#!/bin/sh
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'S3 '||scenario_id||' ttl='||ttl_seconds||'s '||state from arena.oa_chaos_session where scenario_id like '%p8c143439%'"
echo '--- S3 timing 定义（eval-scenarios.yml 本地对照 s3 段）:'
