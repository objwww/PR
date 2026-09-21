#!/bin/sh
# 列 prometheus 全部抓取目标的 URL 与健康态
docker exec deploy-control-app-1 sh -c 'wget -q -O- http://prometheus:9090/api/v1/targets 2>/dev/null' \
  | grep -oE '"scrapeUrl":"[^"]*"' | sed 's/"scrapeUrl"://'
echo ---
docker exec deploy-control-app-1 sh -c 'wget -q -O- http://prometheus:9090/api/v1/targets 2>/dev/null' \
  | grep -oE '"health":"[a-z]*"'
