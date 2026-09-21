#!/bin/sh
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'SINCE='||activated_at from config_bundle_active order by activated_at desc limit 1"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'TERMINAL='||count(*) filter (where state in ('SUCCEEDED','FAILED','EXPIRED','CANCELLED'))||'|FAILED='||count(*) filter (where state in ('FAILED','EXPIRED'))||'|INFLIGHT='||count(*) filter (where state in ('QUEUED','RUNNING','REPORTING')) from rca_run where created_at > (select activated_at from config_bundle_active order by activated_at desc limit 1)"
