#!/bin/sh
# b2-cl06-v2-relaunch.sh —— S0 v2 真窗发射器（CL-06 v2：两批委派 N18 面）
set -u
echo "== 0) 窗口静默 =="
if pgrep -f "e2e-b2-cl0[6]|b2-cl06-retr" >/dev/null 2>&1; then
  echo "DRIVER-RUNNING"; pgrep -af "e2e-b2-cl0[6]|b2-cl06-retr"; exit 1
fi
echo "WINDOW-QUIET"

echo "== 1) 前一轮日志备份 + v2 runs 目录 =="
mkdir -p /opt/build/pr-logs/b2cl06-v2 /opt/build/runs-b2cl06v2
cp -a /opt/build/pr-logs/b2-cl06-v2-*.log /opt/build/pr-logs/b2cl06-v2/ 2>/dev/null || true
ls -1 /opt/build/pr-logs/b2cl06-v2/ | tail -5

echo "== 2) flagd 故障确保（paymentFailure=100%） =="
docker exec flagd-admin-am3 python3 -c "import urllib.request,json;req=urllib.request.Request('http://127.0.0.1:8081/flags',data=json.dumps({'flag':'paymentFailure','variant':'100%'}).encode(),headers={'Content-Type':'application/json'});print(urllib.request.urlopen(req).read().decode())" || exit 1
sleep 20

echo "== 3) 指标面门（error series 可见性） =="
ES=$(docker exec prometheus-am0 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=rpc_client_call_duration_seconds_count%7Berror_type%3D%22UNKNOWN%22%7D' 2>/dev/null | python3 -c "
import sys,json;d=json.load(sys.stdin);rs=d['data']['result']
print(len(rs))")
echo "error series count=${ES:-parse-fail}"
[ "${ES:-0}" -ge 1 ] || { echo "FAULT-NOT-VISIBLE"; exit 1; }

echo "== 4) 发射 retry2 包裹器（固定 3 有效试验） =="
nohup sh /opt/build/b2-cl06-retry2.sh > /opt/build/pr-logs/b2-cl06-v2-retry.log 2>&1 &
echo "retry2 pid=$!"
echo "LAUNCHED $(date -u +%Y-%m-%dT%H:%M:%SZ)"
