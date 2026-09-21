#!/bin/sh
echo '--- 最新路由决策（正确列名）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select stickiness_key||' | bucket='||canary_bucket||' | pct='||percent||' | '||decision||' | '||created_at from canary_route_decision where stickiness_key like '%ArenaOrderStuck%' order by created_at desc limit 6;"
echo '--- 决策分布（近3小时）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select decision||' | '||count(*)||' | max='||max(created_at)::text from canary_route_decision where created_at > now() - interval '3 hours' group by decision;"
