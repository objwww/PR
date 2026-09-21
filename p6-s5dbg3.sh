#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---gen 115 events---'
$PG "select status||'|fingerprint='||fingerprint||'|starts='||starts_at||'|recorded='||recorded_at from alert_event where incident_id='f4cf44b2-a5fd-48fe-875c-69ba3f45d78a' and generation=115 order by recorded_at;"
echo '---run-21 S5 deactivate window (its phases)---'
$PG "select phase, detail::text, created_at from eval_phase_event where eval_run_id::text like '52ba75dd%' and detail::text like '%S5%' order by created_at;"
