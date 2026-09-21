#!/bin/sh
# 验 /api/v1/series 的 match[] 过滤是否真生效（取名字集合）
docker exec -e URL='http://prometheus:9090/api/v1/series?match%5B%5D=%7Bservice%3D%22checkout%22%7D' \
  deploy-control-app-1 sh -c 'wget -q -O- "$URL"' > /tmp/series.json
wc -c /tmp/series.json
grep -o '"__name__":"[a-z_]*"' /tmp/series.json | sort -u | head -12
