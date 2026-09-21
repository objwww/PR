#!/bin/sh
echo '--- 服务器迁移目录文件数与首尾 ---'
ls /opt/build/pr/control-app/src/main/resources/db/migration/ | wc -l
ls /opt/build/pr/control-app/src/main/resources/db/migration/ | sort -t V -k 2 -n | head -3
ls /opt/build/pr/control-app/src/main/resources/db/migration/ | sort -t V -k 2 -n | tail -3
echo '--- migrate 服务定义（compose）---'
grep -A 10 'migrate:' /opt/build/pr/deploy/docker-compose.yml | head -14
echo '--- 历史表行数与最大版本 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*)||' rows, max='||max(version) from flyway_schema_history;"
echo '--- 是否有同名库/库清单 ---'
docker exec deploy-postgres-1 psql -U postgres -tA -c "select datname from pg_database;"
