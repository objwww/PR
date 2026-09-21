#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---rca_run recent---'
$PG "select id, state, created_at from rca_run where created_at > now() - interval '20 minutes' order by created_at desc limit 12;"
echo '---alert_inbox recent (rt fingerprint check)---'
$PG "select state, decision, alert_count, left(payload_digest,12), received_at from alert_inbox where received_at > now() - interval '20 minutes' order by received_at desc limit 12;"
echo '---rt in common_labels---'
$PG "select count(*) from alert_inbox where common_labels::text like '%rt-0%' and received_at > now() - interval '20 minutes';"
