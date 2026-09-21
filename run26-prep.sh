#!/bin/sh
# run26 前置：flagd paymentFailure=100% 打开真故障 → 等 checkout ERROR 行落 Loki
set -e
echo "[prep] flagd-admin health:"
docker exec flagd-admin-am3 python3 -c "import urllib.request;print(urllib.request.urlopen('http://127.0.0.1:8081/health').read().decode())"
echo "[prep] 开 paymentFailure=100%:"
docker exec flagd-admin-am3 python3 -c "import urllib.request,json;req=urllib.request.Request('http://127.0.0.1:8081/flags',data=json.dumps({'flag':'paymentFailure','variant':'100%'}).encode(),headers={'Content-Type':'application/json'});print(urllib.request.urlopen(req).read().decode())"
echo "[prep] 轮询 checkout error 行（最多 24 次×10s）:"
i=0
while [ $i -lt 24 ]; do
  i=$((i+1))
  N=$(docker exec deploy-loki-1 wget -qO- 'http://127.0.0.1:3100/loki/api/v1/query_range?query=%7Bservice_name%3D%22checkout%22%2C%20detected_level%3D%22error%22%7D&limit=1&since=10m' 2>/dev/null | python3 -c "import sys,json;d=json.load(sys.stdin);r=d['data']['result'];print(sum(len(s['values']) for s in r))" 2>/dev/null || echo 0)
  echo "  [$i] error_lines(last 10m)=$N"
  if [ "$N" -gt 0 ]; then
    echo "[prep] 首条错误原文:"
    docker exec deploy-loki-1 wget -qO- 'http://127.0.0.1:3100/loki/api/v1/query_range?query=%7Bservice_name%3D%22checkout%22%2C%20detected_level%3D%22error%22%7D&limit=1&since=10m' 2>/dev/null | head -c 700
    echo
    echo "[prep] PREP_OK"
    exit 0
  fi
  sleep 10
done
echo "[prep] PREP_FAIL：10 分钟无错误行（payment 失败未传导到 checkout 日志）"
exit 1
