#!/bin/sh
INC_KEY='alertname=CheckoutRpcClientErrorRateHigh|service=checkout'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select r.id || ' | ' || r.state || ' | ' || r.created_at from rca_run r join incident i on r.incident_id=i.id where i.incident_key='$INC_KEY' order by r.created_at desc limit 3"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select decision || ' | run=' || coalesce(run_id::text,'-') || ' | ' || created_at from canary_route_decision order by created_at desc limit 2"
exit 0
