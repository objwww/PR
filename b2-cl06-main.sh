#!/bin/sh
# B2 CL-06 合窗主事件：override（FULL+委派批2+缺省委派 prompt）→ 前置门 → 驱动 → 断言
set -u
CD=/opt/build/pr/deploy
OV=/opt/build/b2-cl06-override.yml
OUT=/tmp/b2cl06
rm -rf "$OUT"; mkdir -p "$OUT"

echo "== 0) 窗口静默侦测 =="
NEWRUNS=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c \
  "select count(*) from rca_run where created_at > now() - interval '10 minutes'")
[ "$NEWRUNS" = "0" ] || { echo "ABORT: 近10分钟 $NEWRUNS 个新 run（窗口未静默）"; exit 1; }
CHG=$(find /opt/build/pr -newermt '-10 minutes' \( -name '*.java' -o -name '*.sql' \) 2>/dev/null | head -3)
[ -z "$CHG" ] || { echo "ABORT: 构建树 10 分钟内有改动: $CHG"; exit 1; }
echo "WINDOW-QUIET"

echo "== 1) 挂 override 重建 control-app =="
docker compose -p deploy -f "$CD/docker-compose.yml" -f "$OV" up -d control-app 2>&1 | tail -3
i=0
while [ $i -lt 18 ]; do
  sleep 5
  docker logs deploy-control-app-1 --since 2m 2>&1 | grep -q "Started ControlApplication" && { echo "startup-ok"; break; }
  i=$((i+1))
done
docker inspect deploy-control-app-1 --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | grep -E 'INPUTCAPTURE|MAX_DELEGATION_BATCHES|PRIMARY_PROMPT' > "$OUT/env-override.txt" || true
grep -q 'INPUTCAPTURE=full' "$OUT/env-override.txt" || { echo "FAIL: INPUTCAPTURE 未生效"; exit 1; }
grep -q 'MAX_DELEGATION_BATCHES=2' "$OUT/env-override.txt" || { echo "FAIL: 委派批未生效"; exit 1; }
grep -q '按需委派专家' "$OUT/env-override.txt" || { echo "FAIL: 委派面 prompt 未生效"; exit 1; }
echo "三 env 全生效"

echo "== 2) flagd 故障注入 + 指标面前置门 =="
docker exec flagd-admin-am3 python3 -c "import urllib.request,json;req=urllib.request.Request('http://127.0.0.1:8081/flags',data=json.dumps({'flag':'paymentFailure','variant':'100%'}).encode(),headers={'Content-Type':'application/json'});print(urllib.request.urlopen(req).read().decode())"
sleep 30
docker exec prometheus-am0 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=rpc_client_call_duration_seconds_count%7Berror_type%3D%22UNKNOWN%22%7D' 2>/dev/null | python3 -c "
import sys,json;d=json.load(sys.stdin);rs=d['data']['result']
print('error series:',len(rs),[(s['metric'].get('service_name'),s['value'][1]) for s in rs[:3]])"

echo "== 3) e2e 树外副本 + CRLF 修复 =="
mkdir -p /opt/build/b2tree
cp /opt/build/e2e-b2-cl06.sh /opt/build/e2e-r7-common.sh /opt/build/b2tree/
sed -i 's/\r$//' /opt/build/b2tree/e2e-b2-cl06.sh /opt/build/b2tree/e2e-r7-common.sh
sh -n /opt/build/b2tree/e2e-b2-cl06.sh && echo "syntax-ok" || { echo "FAIL: 语法"; exit 1; }

echo "== 4) 驱动 CL-06 e2e（树外副本）=="
cd /opt/build/b2tree || exit 1
nohup sh -c '. /opt/build/r7-operator-env.sh && R7_RUNS_DIR=/opt/build/runs-b2cl06 sh ./e2e-b2-cl06.sh' \
    > /opt/build/pr-logs/b2-cl06-a0.log 2>&1 &
echo "cl06-launched pid=$!"

echo "== 5) 轮询至驱动收官（最长 20 分钟）=="
i=0
while [ $i -lt 120 ]; do
  sleep 10
  if grep -q 'SUITE PASS' /opt/build/pr-logs/b2-cl06-a0.log 2>/dev/null; then
    echo "driver-finished: SUITE PASS"; break
  fi
  if grep -qE '^\[FAIL\]|r7_fail' /opt/build/pr-logs/b2-cl06-a0.log 2>/dev/null; then
    echo "driver-finished: FAIL（见日志）"; break
  fi
  [ $((i % 6)) -eq 0 ] && tail -1 /opt/build/pr-logs/b2-cl06-a0.log | cut -c1-150
  i=$((i+1))
done
RID=$(find /opt/build/runs-b2cl06 -name run-id.txt -newer "$OV" 2>/dev/null | head -1)
[ -n "$RID" ] && cp "$RID" "$OUT/run-id.txt" && echo "run-id captured: $(cat "$OUT/run-id.txt")" \
  || echo "WARN: run-id 未捕获（driver 可能未到 phase2）"
echo "== 6) 驱动日志尾部 =="
tail -25 /opt/build/pr-logs/b2-cl06-a0.log | cut -c1-170
echo "MAIN-DONE（跨轮 prompt 断言：python3 /opt/build/b2-cl06-verify.py）"
