#!/bin/sh
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select cat || '=' || cnt from (select coalesce(category,'UNCLASSIFIED') as cat, count(*) as cnt from incident group by 1) t order by cnt desc;"
