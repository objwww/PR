#!/bin/sh
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'checkout_30d=' || count(*) from incident where coalesce(substring(incident_key from 'service=([^|]+)'),'（未知服务）')='checkout' and first_seen_at >= now() - interval '30 days';"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'owners=' || count(*) from service_owner;"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'flyway=' || version from flyway_schema_history where success order by installed_rank desc limit 1;"
