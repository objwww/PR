#!/bin/sh
cd /opt/build/pr/deploy
BEARER=$(grep '^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=' .env | cut -d= -f2- | tr -d '\r"')
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
docker run --rm --network alert-net curlimages/curl:latest -s -o /dev/null -w 'resolved-post=%{http_code}\n' \
  -H "Authorization: Bearer $BEARER" -H 'Content-Type: application/json' \
  -X POST http://control-app:8080/webhooks/alertmanager \
  -d "{\"version\":\"4\",\"receiver\":\"oncall\",\"groupKey\":\"cleanup::duporders\",\"truncatedAlerts\":0,\"status\":\"resolved\",\"commonLabels\":{},\"commonAnnotations\":{},\"externalURL\":\"\",\"alerts\":[{\"status\":\"resolved\",\"labels\":{\"alertname\":\"ArenaDuplicateOrders\",\"service\":\"order-arena\",\"job\":\"order-arena\",\"instance\":\"order-arena:8080\",\"severity\":\"page\",\"fault_type\":\"F1\"},\"annotations\":{\"summary\":\"cleanup\"},\"startsAt\":\"2026-09-18T19:01:00Z\",\"endsAt\":\"$NOW\",\"fingerprint\":\"0d7404ae811ae84a\"}]}"
sleep 6
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select incident_key||' '||status from incident where incident_key like 'alertname=ArenaDuplicateOrders%';"
