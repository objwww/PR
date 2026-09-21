#!/bin/sh
cd /opt/build/pr/deploy
TOK=$(grep '^CHAOS_ADMIN_TOKEN=' .env | cut -d= -f2- | tr -d '\r"')
for FT in F1 F2 F3; do
  echo "---off $FT---"
  docker run --rm --network eval-mgmt curlimages/curl:latest -s -X POST "http://arena-chaos-admin:8080/chaos/$FT/off" -H "X-Admin-Token: $TOK" -H 'Content-Type: application/json' -d '{}' --max-time 8 2>/dev/null | head -c 200
  echo ''
done
echo '---S3 incident state---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select incident_key||' '||status from incident where incident_key like 'alertname=ArenaDuplicateOrders%';"
