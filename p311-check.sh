#!/bin/sh
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from eval_run;"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select id, state, started_at from eval_run order by started_at desc limit 3;"
echo '=== 真实数据集名 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select dataset_name, dataset_version from eval_dataset_tier;"
