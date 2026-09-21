#!/bin/sh
echo '=== 迁移最高版本 ==='
ls /opt/build/pr/control-app/src/main/resources/db/migration/ | sort -V | tail -3
echo '=== eval 表清单 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select table_name from information_schema.tables where table_name like 'eval%' or table_name like 'regression%' order by 1;"
echo '=== 数据集与实验行样例 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select column_name from information_schema.columns where table_name='eval_dataset' order by ordinal_position;" 2>/dev/null || true
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select column_name from information_schema.columns where table_name='eval_run' order by ordinal_position;" 2>/dev/null | head -20
echo '=== 数据集清单 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select dataset_name, dataset_version from eval_dataset group by 1,2 limit 5;" 2>/dev/null || true
