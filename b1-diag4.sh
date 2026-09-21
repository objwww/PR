#!/bin/sh
# 容器清单 + payment/checkout 日志采样 + locust 面
echo "== 1) 全容器清单 =="
docker ps --format '{{.Names}}\t{{.Status}}' | sort
echo "== 2) payment 最近 3 行原文 =="
docker exec deploy-loki-1 wget -qO- 'http://127.0.0.1:3100/loki/api/v1/query_range?query=%7Bservice_name%3D%22payment%22%7D&limit=3&since=10m' 2>/dev/null | python3 -c "
import sys,json
d=json.load(sys.stdin)
for s in d['data']['result']:
    for v in s['values']: print(' ',v[1][:180])" 2>/dev/null
echo "== 3) checkout 最近 3 行原文 =="
docker exec deploy-loki-1 wget -qO- 'http://127.0.0.1:3100/loki/api/v1/query_range?query=%7Bservice_name%3D%22checkout%22%7D&limit=3&since=10m' 2>/dev/null | python3 -c "
import sys,json
d=json.load(sys.stdin)
for s in d['data']['result']:
    for v in s['values']: print(' ',v[1][:180])" 2>/dev/null
echo "== 4) load-generator 内部状态 =="
docker logs load-generator --tail 5 2>&1 | cut -c1-180
docker exec load-generator sh -c 'wget -qO- http://127.0.0.1:8089/stats/requests 2>/dev/null | head -c 400' 2>/dev/null || echo "(locust stats 面不可达——8089 端口猜测)"
