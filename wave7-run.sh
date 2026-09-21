#!/bin/sh
set -e
cd /opt/build/pr
tar xzf /tmp/wave7.tar.gz
cd deploy
docker compose up migrate 2>&1 | tail -1
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'PRICING '||model||' in='||input_micros_per_1k||' out='||output_micros_per_1k from model_pricing order by 1"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'SAMPLE '||m.requested_model||' cost_micros='||round(((m.usage->>'prompt_tokens')::bigint*p.input_micros_per_1k+coalesce((m.usage->>'completion_tokens')::bigint,0)*p.output_micros_per_1k)/1000.0) from rca_model_call m join model_pricing p on p.model=m.requested_model limit 3"
