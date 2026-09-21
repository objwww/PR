#!/bin/sh
cd /opt/build/pr/deploy
TOK=$(grep '^CHAOS_ADMIN_TOKEN=' .env | cut -d= -f2- | tr -d '\r"')
for S in S3 S4 S5; do
  echo "---status $S---"
  docker run --rm --network eval-mgmt curlimages/curl:latest -s "http://arena-chaos-admin:8080/chaos/status?scenarioId=$S" -H "X-Admin-Token: $TOK" --max-time 8 2>/dev/null | head -c 400
  echo ''
done
echo '---all firing arena incidents---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select incident_key||' '||status from incident where incident_key like 'alertname=Arena%' and status='FIRING';"
