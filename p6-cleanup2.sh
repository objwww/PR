#!/bin/sh
cd /opt/build/pr/deploy
BEARER=$(grep '^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=' .env | cut -d= -f2- | tr -d '\r"')
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
docker run --rm --network alert-net curlimages/curl:latest -s -o /dev/null -w 'resolved-post=%{http_code}\n' \
  -H "Authorization: Bearer $BEARER" -H 'Content-Type: application/json' \
  -X POST http://control-app:8080/webhooks/alertmanager \
  -d "{\"version\":\"4\",\"receiver\":\"oncall\",\"groupKey\":\"cleanup::verify\",\"truncatedAlerts\":0,\"status\":\"resolved\",\"commonLabels\":{},\"commonAnnotations\":{},\"externalURL\":\"\",\"alerts\":[{\"status\":\"resolved\",\"labels\":{\"alertname\":\"ArenaDuplicateOrders\",\"service\":\"order-arena\",\"severity\":\"warning\"},\"annotations\":{\"summary\":\"cleanup stale verify probe\"},\"startsAt\":\"2026-09-17T13:06:00Z\",\"endsAt\":\"$NOW\",\"fingerprint\":\"verify2-1789650403\"}]}"
sleep 6
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select incident_key||' '||status from incident where incident_key like 'alertname=ArenaDuplicateOrders%' or incident_key like 'alertname=ArenaIllegalTransitions%' or incident_key like 'alertname=ArenaOrderStuck%';"
