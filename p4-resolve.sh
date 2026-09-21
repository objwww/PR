#!/bin/sh
# P4 清场：run-10 被 kill 遗留的 FIRING 事故（rt2-01/rt2-02）补发 resolved 变体
cd /opt/build/pr/deploy
BEARER=$(grep '^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=' .env | cut -d= -f2- | tr -d '\r"')
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)

resolve_one() {
  FP=$1; ALERTNAME=$2; SERVICE=$3
  cat <<EOF | curl -s -o /dev/null -w "$ALERTNAME resolved-post=%{http_code}\n" \
    -H "Authorization: Bearer $BEARER" -H 'Content-Type: application/json' \
    -X POST http://127.0.0.1:8080/webhooks/alertmanager --data @-
{"version":"4","receiver":"oncall","groupKey":"rt2::$FP","truncatedAlerts":0,
 "status":"resolved","commonLabels":{},"commonAnnotations":{},"externalURL":"",
 "alerts":[{"status":"resolved",
   "labels":{"alertname":"$ALERTNAME","service":"$SERVICE","job":"$SERVICE",
     "severity":"page","category":"availability","owner":"am0"},
   "annotations":{"summary":"$ALERTNAME resolved (cleanup)"},
   "startsAt":"2026-09-17T03:40:00Z","endsAt":"$NOW","fingerprint":"$FP"}]}
EOF
}

resolve_one rt2-01 PaymentChargeFailure payment
resolve_one rt2-02 DbConnectionsSaturated database
sleep 8
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c \
  "select incident_key||' '||status from incident where incident_key like 'alertname=PaymentChargeFailure%' or incident_key like 'alertname=DbConnectionsSaturated%';"
