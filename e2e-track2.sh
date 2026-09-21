#!/bin/sh
INC=c2070875-1fdb-4320-84cd-ca2b4113bb70
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select status || ' | waiting=' || coalesce(waiting_reason,'-') || ' | fired_at=' || coalesce(last_firing_starts_at::text,'-') || ' | events=' || distinct_event_count from incident where id='$INC'"
echo '=== control-app 近 3m 相关日志 ==='
docker logs deploy-control-app-1 --since 3m 2>&1 | grep -iE "$INC|rca|supervisor|schedule" | tail -15
exit 0
