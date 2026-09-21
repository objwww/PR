#!/bin/sh
# B1-1 FULL 捕获主事件（第三版：override 重挂 + 树外 e2e 副本防 CRLF）
set -u
CD=/opt/build/pr/deploy
OV=/opt/build/b1-fullcap-override.yml
OUT=/tmp/b1fullcap
rm -rf "$OUT"; mkdir -p "$OUT"

echo "== 1) 重挂 override 重建 control-app =="
docker compose -p deploy -f "$CD/docker-compose.yml" -f "$OV" up -d control-app 2>&1 | tail -3
i=0
while [ $i -lt 18 ]; do
  sleep 5
  docker logs deploy-control-app-1 --since 2m 2>&1 | grep -q "Started ControlApplication" && { echo "startup-ok"; break; }
  i=$((i+1))
done
docker inspect deploy-control-app-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -i INPUTCAPTURE \
  || { echo "FAIL: INPUTCAPTURE 未生效"; exit 1; }

echo "== 2) 前置门（不依赖 checkout error 行——用 payment 失败行+指标面）=="
docker exec deploy-loki-1 wget -qO- 'http://127.0.0.1:3100/loki/api/v1/query_range?query=%7Bservice_name%3D%22payment%22%7D%7C%7C~%22Payment+request+failed%22&limit=1&since=5m' 2>/dev/null | grep -q "Payment request failed" \
  && echo "payment-failure-lines: OK" || echo "payment-failure-lines: NONE（flagd 可能被重置——重注入）"
docker exec flagd-admin-am3 python3 -c "import urllib.request,json;req=urllib.request.Request('http://127.0.0.1:8081/flags',data=json.dumps({'flag':'paymentFailure','variant':'100%'}).encode(),headers={'Content-Type':'application/json'});print(urllib.request.urlopen(req).read().decode())"
sleep 30
docker exec prometheus-am0 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=rpc_client_call_duration_seconds_count%7Berror_type%3D%22UNKNOWN%22%7D' 2>/dev/null | python3 -c "
import sys,json;d=json.load(sys.stdin);rs=d['data']['result']
print('error series:',len(rs),[(s['metric'].get('service_name'),s['value'][1]) for s in rs[:3]])"

echo "== 3) e2e 树外副本 + CRLF 修复 =="
ES=/opt/build/pr/docs/测试证据/R7/e2e-脚本
cp "$ES/e2e-r7-a0-provider-receipt-chain.sh" /opt/build/e2e-a0-b1.sh
cp "$ES/e2e-r7-common.sh" /opt/build/e2e-r7-common.sh
sed -i 's/\r$//' /opt/build/e2e-a0-b1.sh /opt/build/e2e-r7-common.sh
sh -n /opt/build/e2e-a0-b1.sh && echo "syntax-ok" || { echo "FAIL: 语法"; exit 1; }

echo "== 4) 驱动 A0（树外副本）=="
cd /opt/build || exit 1
nohup sh -c '. /opt/build/r7-operator-env.sh && sh /opt/build/e2e-a0-b1.sh' \
    > /opt/build/pr-logs/b1-fullcap-a0.log 2>&1 &
echo "a0-launched pid=$!"

echo "== 5) 轮询新 run 至终态（最长 15 分钟）=="
i=0; RUN_ID=""
while [ $i -lt 90 ]; do
  sleep 10
  ROW=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c \
    "select id::text||'|'||state from rca_run where created_at > now() - interval '20 minutes' order by created_at desc limit 1;")
  case "$ROW" in
    "") : ;;
    *SUCCEEDED*|*FAILED*|*CANCELLED*|*DEAD*|*TIMEOUT*) echo "  [$i] 终态: $ROW"; RUN_ID="${ROW%%|*}"; break ;;
    *) [ $((i % 6)) -eq 0 ] && echo "  [$i] $ROW" ;;
  esac
  i=$((i+1))
done
echo "RUN_ID=$RUN_ID" | tee "$OUT/run-id.txt"
[ -n "$RUN_ID" ] || { echo "WARN: 未捕获终态 run——需人工接管"; }
echo "== 6) A0 日志尾部 =="
tail -30 /opt/build/pr-logs/b1-fullcap-a0.log | cut -c1-170
echo "MAIN-DONE"
