#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---rt incidents by fingerprint fragment---'
$PG "select incident_key, status, category, category_rule_id from incident where incident_key::text like '%f95e%' or incident_key::text like '%payment%' or incident_key::text like '%rt%' order by last_event_at desc limit 8;"
echo '---rt-01 run engine/purpose---'
$PG "select id, engine, purpose, purpose_source, trigger_kind from rca_run where id='e19efae5-379e-4ba6-94b2-86f618312b21';"
echo '---drill run engine/purpose---'
$PG "select id, engine, purpose, purpose_source, trigger_kind from rca_run where id='d2764dfc-a809-4e3e-8b3f-2a91bbb746bf';"
echo '---incident for e19efae5---'
$PG "select i.incident_key, i.category, i.category_rule_id from rca_run r join incident i on i.id=r.incident_id where r.id='e19efae5-379e-4ba6-94b2-86f618312b21';"
