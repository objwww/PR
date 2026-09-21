#!/bin/sh
cd /opt/build/pr/deploy
TOK=$(grep '^CHAOS_ADMIN_TOKEN=' .env | cut -d= -f2- | tr -d '\r"')
echo '---container networks---'
docker inspect alert-arena-chaos-admin-1 --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}'
echo ''
echo '---probe with errors (eval-mgmt)---'
docker run --rm --network eval-mgmt curlimages/curl:latest -sv --max-time 8 "http://arena-chaos-admin:8080/chaos/status?scenarioId=S3" -H "Authorization: Bearer $TOK" 2>&1 | tail -8
