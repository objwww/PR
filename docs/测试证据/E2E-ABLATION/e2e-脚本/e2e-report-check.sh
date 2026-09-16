#!/bin/sh
RUN=82cf4cbf-44fd-4ea0-9184-84115a53e9f0
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select r.id || ' | state=' || r.state || ' | engine=' || coalesce(r.engine,'-') from rca_run r where r.id='$RUN'"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select run_id || ' | report_severity=' || coalesce(package_json->'summary'->>'severity','?') || ' | headline=' || coalesce(package_json->'summary'->>'headline', package_json->'summary'->>'conclusion','?') from rca_report where run_id='$RUN'"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from rca_model_call where run_id='$RUN'"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from rca_tool_invocation where run_id='$RUN'"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from rca_evidence where run_id='$RUN'"
exit 0
