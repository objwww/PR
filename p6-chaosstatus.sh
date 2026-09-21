#!/bin/sh
cd /opt/build/pr/deploy
TOK=$(grep '^CHAOS_ADMIN_TOKEN=' .env | cut -d= -f2- | tr -d '\r"')
for FT in F1 F2 F3; do
  echo "---status $FT---"
  docker run --rm --network alert-net curlimages/curl:latest -s "http://arena-chaos-admin:8080/chaos/status?scenarioId=S$FT" -H "Authorization: Bearer $TOK" 2>/dev/null | head -c 300
  echo ''
done
