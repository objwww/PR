#!/bin/sh
# checkout ERROR 突发节律：近 4h 每 20m 桶计数 + 最近 5 条原文
curl -sG 'http://127.0.0.1:3100/loki/api/v1/query_range' \
  --data-urlencode 'query=sum(count_over_time({service_name="checkout"} |~ "ERROR" [20m])) by (job)' \
  --data-urlencode 'since=4h' | head -c 1500
echo
echo '--- last 3 raw ERROR lines ---'
curl -sG 'http://127.0.0.1:3100/loki/api/v1/query_range' \
  --data-urlencode 'query={service_name="checkout"} |~ "ERROR"' \
  --data-urlencode 'limit=3' --data-urlencode 'since=24h' | head -c 1200
echo
