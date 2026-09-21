#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent"
echo '---FIRING incident rows---'
$PG -c "select id::text, incident_key, status from incident where status='FIRING' and incident_key like 'alertname=Arena%';"
echo '---their latest firing event fingerprints---'
$PG -c "select distinct ae.fingerprint, ae.status, i.incident_key from alert_event ae join incident i on i.id=ae.incident_id where i.status='FIRING' and i.incident_key like 'alertname=Arena%' order by ae.fingerprint;"
