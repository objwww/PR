#!/bin/sh
cd /opt/build/pr/deploy
TOK=$(grep '^CHAOS_ADMIN_TOKEN=' .env | cut -d= -f2- | tr -d '\r"')
for FT in F1 F2 F3; do
  echo "---off $FT http code---"
  docker run --rm --network eval-mgmt curlimages/curl:latest -s -o /dev/null -w '%{http_code}\n' -X POST "http://arena-chaos-admin:8080/chaos/$FT/off" -H "X-Admin-Token: $TOK" -H 'Content-Type: application/json' --max-time 8 2>/dev/null
done
echo '---chaos-admin own log tail---'
docker logs alert-arena-chaos-admin-1 --tail 15 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | cut -c1-180
