#!/bin/sh
echo '--- 22:00 后 ArenaOrderStuck inbox 行状态分布 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select state||' | '||coalesce(decision::text,'-')||' | '||count(*) from alert_inbox where received_at > '2026-09-16 22:00:00+00' and convert_from(payload_raw,'UTF8') like '%\"alertname\":\"ArenaOrderStuck\"%' group by state, decision order by 3 desc;"
echo '--- 最新 3 行详情 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select received_at||' | '||state||' | '||coalesce(decision::text,'-')||' | digest='||left(payload_digest,12) from alert_inbox where received_at > '2026-09-16 22:00:00+00' and convert_from(payload_raw,'UTF8') like '%\"alertname\":\"ArenaOrderStuck\"%' order by received_at desc limit 3;"
echo '--- 决策表近3小时 stickiness 样本（去重 5 个）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select distinct stickiness_key from canary_route_decision where created_at > now() - interval '3 hours' limit 5;"
