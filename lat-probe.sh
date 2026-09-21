#!/bin/sh
# 三型请求时延：catalog(label values) / metric_value(instant query) / loki aggregate
NOW=$(date +%s)
for i in 1 2 3; do
  for U in "catalog http://prometheus:9090/api/v1/label/__name__/values?match=%7Bservice%3D%22checkout%22%7D" \
           "mval http://prometheus:9090/api/v1/query?query=http_server_request_duration_seconds_count%7Bservice%3D%22checkout%22%7D&time=$NOW" \
           "loki http://loki:3100/loki/api/v1/query_range?query=%7Bservice%3D%22checkout%22%7D&limit=10&start=$(( (NOW-600)*1000000000 ))&end=$(( NOW*1000000000 ))"; do
    K=${U%% *}; URL=${U#* }
    S=$(date +%s%N)
    docker exec deploy-control-app-1 wget -q -T 10 -O /dev/null "$URL" 2>/dev/null
    RC=$?
    E=$(date +%s%N)
    echo "$K try$i rc=$RC ns_start=$S ns_end=$E"
  done
done
