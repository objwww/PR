#!/bin/sh
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select verdict||' | '||coalesce(category,'-')||' | '||left(coalesce(actual_cause,'-'),20)||' | '||created_at from conclusion_feedback order by created_at desc limit 3"
