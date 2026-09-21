#!/bin/sh
echo '=== native 段键 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select string_agg(k, ', ') from (select jsonb_object_keys(content->'native') k from config_bundle where revision=151) t;"
echo '=== native 段样例（截断） ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(content->'native'::text, 500) from config_bundle where revision=151;"
