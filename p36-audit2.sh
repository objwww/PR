#!/bin/sh
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select coalesce(category,'UNCLASSIFIED') || '=' || count(*) from incident group by 1 order by 2 desc;"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'total=' || count(*) from incident;"
