#!/bin/sh
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "GRANT SELECT ON rca_tool_invocation, rca_evidence TO eval_app;"
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "set role eval_app; select 'inv_readable='||count(*) from rca_tool_invocation;"
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "set role eval_app; select 'evid_readable='||count(*) from rca_evidence;"
