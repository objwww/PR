#!/bin/sh
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'total=' || count(*) from release_asset where asset_kind='PROMPT';"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'roles=' || count(distinct content->>'role') from release_asset where asset_kind='PROMPT' and content->>'role' is not null;"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'inprod=' || count(distinct r.content->>'role_digest') from release_asset r where r.asset_kind='PROMPT' and r.content->>'role_digest' in (select distinct role_digest from rca_model_call);"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'active_rev=' || b.revision from config_bundle_active a join config_bundle b on b.bundle_digest=a.bundle_digest limit 1;"
