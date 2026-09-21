#!/bin/sh
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select coalesce(requested_model,'?')||' | '||count(*) from rca_model_call group by requested_model"
