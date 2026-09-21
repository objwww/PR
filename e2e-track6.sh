#!/bin/sh
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select jsonb_pretty(b.content->'canary') from config_bundle b join config_bundle_active a on a.bundle_digest=b.bundle_digest"
exit 0
