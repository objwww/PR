#!/bin/sh
cd /opt/build/pr/deploy
TOK=$(grep '^CHAOS_ADMIN_TOKEN=' .env | cut -d= -f2- | tr -d '\r"')
for FT in F1 F2 F3; do
  echo "---off $FT---"
  docker run --rm --network alert-net curlimages/curl:latest -s -X POST "http://arena-chaos-admin:8080/chaos/$FT/off" -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' 2>/dev/null | head -c 200
  echo ''
done
